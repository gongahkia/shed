package shed.api;

/**
 * Entry point for a Shed Java extension.
 *
 * <p>An extension is loaded only after a user-installed, checksum-verified
 * receipt authorizes its JAR. Implementations should release listeners and
 * resources from {@link #deactivate()}.</p>
 */
public interface ShedExtension {
    void activate(ExtensionContext context) throws Exception;

    default void deactivate() throws Exception {
    }
}
