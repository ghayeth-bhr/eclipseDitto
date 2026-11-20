package org.naruprof.twin.service.impl;

import org.naruprof.twin.dal.api.LearningSource;
import org.naruprof.twin.dal.api.SourceException;
import org.naruprof.twin.infra.LearningSummary;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.SourceState;
import org.naruprof.twin.infra.U64;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Scriptable source: a gap-free in-memory log, snapshot = head, watch = replay + manual push. */
class ScriptedSource implements LearningSource {

    final ProfileId profile;
    final List<SourceState> log = new ArrayList<>();
    final List<Listener> listeners = new CopyOnWriteArrayList<>();
    final List<U64> watchCursors = new CopyOnWriteArrayList<>();
    SourceException snapshotFailure;
    int snapshots;

    ScriptedSource(ProfileId profile) {
        this.profile = profile;
        log.add(new SourceState.Live(profile, U64.ZERO, LearningSummary.EMPTY));
    }

    synchronized SourceState commit(long t, long p, long props, long chains) {
        SourceState s = new SourceState.Live(profile, U64.of(log.size()), LearningSummary.of(t, p, props, chains));
        log.add(s);
        listeners.forEach(l -> l.onState(s));
        pumpAll();
        return s;
    }

    private void pumpAll() {
        for (FlowStream f : flows) {
            new Thread(f::pump).start(); // deliver on a "transport" thread
        }
    }

    synchronized SourceState delete() {
        SourceState s = new SourceState.Tombstone(profile, U64.of(log.size()));
        log.add(s);
        listeners.forEach(l -> l.onState(s));
        pumpAll();
        return s;
    }

    synchronized void rewindTo(long seq) {
        while (log.size() > seq + 1) {
            log.remove(log.size() - 1);
        }
    }

    SourceState.Live live(long seq) {
        return (SourceState.Live) log.get((int) seq);
    }

    void push(SourceState s) {
        listeners.forEach(l -> l.onState(s));
        flows.forEach(f -> f.l.onState(s));
    }

    void fail(SourceException e) {
        listeners.forEach(l -> l.onError(e));
        for (FlowStream f : flows) {
            f.close();
            f.l.onError(e);
        }
    }

    @Override
    public synchronized SourceState getSnapshot(ProfileId p) {
        snapshots++;
        if (snapshotFailure != null) {
            throw snapshotFailure;
        }
        return log.get(log.size() - 1);
    }

    @Override
    public synchronized Subscription watch(ProfileId p, U64 after, int window, Listener l) {
        watchCursors.add(after);
        if (after.bits() > log.size() - 1) {
            l.onError(new SourceException(SourceException.Kind.OUT_OF_RANGE, "ahead"));
            return new Subscription() {
                public void request(int n) { }
                public void close() { }
            };
        }
        if (ignoreFlowControl) {
            for (long s = after.bits() + 1; s < log.size(); s++) {
                l.onState(log.get((int) s));
            }
            listeners.add(l);
            return new Subscription() {
                public void request(int n) { }
                public void close() { listeners.remove(l); }
            };
        }
        // flow-controlled replay + tail driven by request(n), like a gRPC server honoring demand
        FlowStream fs = new FlowStream(l, after.bits() + 1, window);
        flows.add(fs);
        fs.pump();
        return fs;
    }

    /** When true the source pushes regardless of demand (a misbehaving producer). */
    volatile boolean ignoreFlowControl;
    final List<FlowStream> flows = new CopyOnWriteArrayList<>();

    final class FlowStream implements Subscription {
        final Listener l;
        long next;
        int demand;
        volatile boolean closed;

        FlowStream(Listener l, long next, int demand) {
            this.l = l;
            this.next = next;
            this.demand = demand;
        }

        void pump() {
            while (true) {
                SourceState s;
                synchronized (ScriptedSource.this) {
                    if (closed || demand <= 0 || next >= log.size()) {
                        return;
                    }
                    s = log.get((int) next++);
                    demand--;
                }
                l.onState(s);
            }
        }

        @Override
        public void request(int n) {
            synchronized (ScriptedSource.this) {
                demand += n;
            }
            pump();
        }

        @Override
        public void close() {
            closed = true;
            flows.remove(this);
        }
    }
}
