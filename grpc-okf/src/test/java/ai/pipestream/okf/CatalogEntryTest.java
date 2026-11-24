package ai.pipestream.okf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogEntryTest {

    @Test
    void copiesResourceBodyAndRejectsBlankTargetUri() {
        byte[] payload = {1, 2, 3};
        CatalogEntry entry = new CatalogEntry(
                "pages/200.md",
                OkfConcept.of("Page").title("Doc").build(),
                "https://wiki.example/pages/200",
                "text/plain",
                payload,
                "page");
        payload[0] = 9;
        assertThat(entry.resourceBody()[0]).isEqualTo((byte) 1);
        assertThat(entry.title()).isEqualTo("Doc");
        assertThatThrownBy(() -> new CatalogEntry(
                "pages/200.md",
                OkfConcept.of("Page").build(),
                "  ",
                "text/plain",
                new byte[0],
                "page"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetUri");
    }

    @Test
    void anEntryBuiltFromBytesIsCaptured() {
        CatalogEntry entry = new CatalogEntry("pages/200.md", OkfConcept.of("Page").build(),
                "https://wiki.example/pages/200", "text/html", new byte[] {1}, "page");

        assertThat(entry.capture()).isEqualTo(CatalogEntry.Capture.CAPTURED);
        assertThat(entry.captured()).isTrue();
        assertThat(CatalogEntry.text("pages/200.md", OkfConcept.of("Page").build(),
                "https://wiki.example/pages/200", "text/plain", "Doc", "page").captured())
                .isTrue();
    }

    @Test
    void aNotFetchedEntryKeepsTheMediaTypeAndHasNoBody() {
        CatalogEntry entry = CatalogEntry.notFetched("attachments/9.md",
                OkfConcept.of("Attachment").title("report.pdf").build(),
                "https://wiki.example/download/report.pdf", "application/pdf", "attachment");

        assertThat(entry.capture()).isEqualTo(CatalogEntry.Capture.NOT_FETCHED);
        assertThat(entry.captured()).isFalse();
        assertThat(entry.mediaType()).isEqualTo("application/pdf");
        assertThat(entry.resourceBody()).isEmpty();
        // The markdown says so as well, and keeps the rest of the concept.
        assertThat(entry.concept().extra()).containsEntry(CatalogEntry.CAPTURE_KEY, "not_fetched");
        assertThat(entry.concept().title()).contains("report.pdf");
        assertThat(OkfYaml.render(entry.concept())).contains("capture: not_fetched");
    }

    @Test
    void aCapturedEntryHasNoCaptureKey() {
        CatalogEntry entry = CatalogEntry.text("pages/200.md",
                OkfConcept.of("Page").title("Doc").build(), "https://wiki.example/pages/200",
                "text/html", "<p>hi</p>", "page");

        assertThat(entry.concept().extra()).doesNotContainKey(CatalogEntry.CAPTURE_KEY);
    }

    @Test
    void aNotFetchedEntryCannotCarryABody() {
        assertThatThrownBy(() -> new CatalogEntry("attachments/9.md",
                OkfConcept.of("Attachment").build(), "https://wiki.example/download/report.pdf",
                "application/pdf", "report.pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "attachment", CatalogEntry.Capture.NOT_FETCHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not fetched");
    }
}
