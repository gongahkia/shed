package shed.api;

/** A command contributed to Shed's command palette and Ex command dispatcher. */
@FunctionalInterface
public interface ExtensionCommand {
    String execute(String arguments) throws Exception;
}
