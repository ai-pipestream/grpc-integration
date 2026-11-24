package ai.pipestream.okf;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * One entity ready for an OKF concept and, when its bytes were captured, a
 * WARC {@code resource} record. {@code targetUri} is the live URI used as
 * {@code WARC-Target-URI} and as the collection-page {@code href}.
 *
 * <p>A source that read only an entity's metadata builds the entry with
 * {@link #notFetched}: such an entry has an OKF concept and a collection
 * page link, and no {@code resource} record, because nothing that lives at
 * {@code targetUri} was captured. Its concept carries the frontmatter key
 * {@value #CAPTURE_KEY} with the value {@code not_fetched}, so the markdown
 * says so too. A resource body is never made up to stand in for bytes that
 * were not read.
 *
 * @param path bundle-relative markdown path
 * @param concept OKF concept
 * @param targetUri live resource URI
 * @param mediaType MIME type of the resource at {@code targetUri}
 * @param resourceBody bytes stored in the WARC resource record (not the
 *        ZIP); empty when {@code capture} is {@link Capture#NOT_FETCHED}
 * @param kind short type label for the collection page
 * @param capture whether {@code resourceBody} holds the resource's bytes
 */
public record CatalogEntry(
        String path,
        OkfConcept concept,
        String targetUri,
        String mediaType,
        byte[] resourceBody,
        String kind,
        Capture capture) {

    /** Frontmatter key a concept carries when its resource was not fetched. */
    public static final String CAPTURE_KEY = "capture";

    /** Whether the bytes of the resource at {@code targetUri} were captured. */
    public enum Capture {
        /** {@code resourceBody} is what was read for {@code targetUri}. */
        CAPTURED("captured"),
        /** Only metadata was read; the resource's bytes were not fetched. */
        NOT_FETCHED("not_fetched");

        private final String wire;

        Capture(String wire) {
            this.wire = wire;
        }

        /**
         * The frontmatter value.
         *
         * @return {@code captured} or {@code not_fetched}
         */
        public String wire() {
            return wire;
        }
    }

    /**
     * Copies the resource body.
     *
     * @param path markdown path
     * @param concept concept
     * @param targetUri live URI
     * @param mediaType MIME type
     * @param resourceBody WARC resource payload
     * @param kind collection-page kind
     * @param capture whether the payload was captured
     */
    public CatalogEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(concept, "concept");
        Objects.requireNonNull(targetUri, "targetUri");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(resourceBody, "resourceBody");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(capture, "capture");
        if (targetUri.isBlank()) {
            throw new IllegalArgumentException("targetUri is required");
        }
        if (capture == Capture.NOT_FETCHED) {
            if (resourceBody.length != 0) {
                throw new IllegalArgumentException(
                        "an entry whose resource was not fetched has no resource body");
            }
            concept = concept.toBuilder().extra(CAPTURE_KEY, capture.wire()).build();
        }
        resourceBody = resourceBody.clone();
    }

    /**
     * An entry whose resource body was captured.
     *
     * @param path markdown path
     * @param concept concept
     * @param targetUri live URI
     * @param mediaType MIME type
     * @param resourceBody WARC resource payload
     * @param kind collection-page kind
     */
    public CatalogEntry(String path, OkfConcept concept, String targetUri, String mediaType,
            byte[] resourceBody, String kind) {
        this(path, concept, targetUri, mediaType, resourceBody, kind, Capture.CAPTURED);
    }

    /**
     * An entry for an entity whose bytes were not fetched: metadata only.
     *
     * @param path markdown path
     * @param concept concept
     * @param targetUri live URI
     * @param mediaType MIME type the source reports for the resource; empty
     *        when it reports none
     * @param kind collection-page kind
     * @return the entry, with {@link Capture#NOT_FETCHED} and no resource body
     */
    public static CatalogEntry notFetched(String path, OkfConcept concept, String targetUri,
            String mediaType, String kind) {
        return new CatalogEntry(path, concept, targetUri, mediaType, new byte[0], kind,
                Capture.NOT_FETCHED);
    }

    /**
     * Whether this entry carries the bytes of its resource.
     *
     * @return true when {@link #capture()} is {@link Capture#CAPTURED}
     */
    public boolean captured() {
        return capture == Capture.CAPTURED;
    }

    /**
     * Display title for indexes and the collection page.
     *
     * @return title
     */
    public String title() {
        return concept.title().orElse(path);
    }

    /**
     * UTF-8 helper when the resource body is text.
     *
     * @param path markdown path
     * @param concept concept
     * @param targetUri live URI
     * @param mediaType MIME type
     * @param text resource text
     * @param kind kind
     * @return the entry
     */
    public static CatalogEntry text(String path, OkfConcept concept, String targetUri,
            String mediaType, String text, String kind) {
        byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        return new CatalogEntry(path, concept, targetUri, mediaType, bytes, kind);
    }
}
