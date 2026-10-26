package shed;

/** Deterministic child command; no editor, network, provider or model. */
public class AgentCheckFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "fail" -> { System.out.println("Synthetic check failed"); System.exit(7); }
            case "large" -> System.out.print("x".repeat(100000));
            case "wait" -> { System.out.println("ready"); Thread.sleep(60000); }
            case "child" -> {
                var command = AcpConnectionTest.javaCommand(AgentCheckFixture.class.getName(), "wait");
                var child = new ProcessBuilder(command).inheritIO().start();
                AtomicFileWriter.write(java.nio.file.Path.of(args[1]), Long.toString(child.pid()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                child.waitFor();
            }
            default -> System.out.println("Synthetic check passed");
        }
    }
}
