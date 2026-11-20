package org.naruprof.twin.contract;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The vendored proto must stay byte-identical to the shared contract distributed to all four agents. */
class ContractChecksumTest {

    /** SHA-256 of implementation-prompts/contracts/learning_projection_v1.proto (integration contract v1). */
    static final String CONTRACT_SHA256 = "d390fc11d195e1bb6bd38988b69668c7200a2c7745325baad74bdb34b32ad290";

    @Test
    void vendoredProtoMatchesPublishedContract() throws Exception {
        Path vendored = Path.of("src/main/proto/learning_projection_v1.proto");
        assertThat(sha256(vendored)).isEqualTo(CONTRACT_SHA256);
        Path shared = Path.of("../../implementation-prompts/contracts/learning_projection_v1.proto");
        assumeTrue(Files.exists(shared), "shared contracts directory not present next to the module");
        assertThat(sha256(shared)).isEqualTo(CONTRACT_SHA256);
    }

    private static String sha256(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }
}
