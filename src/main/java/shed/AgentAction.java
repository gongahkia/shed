package shed;

enum AgentAction {
    ASK("Ask/search"), EDIT("Edit selection"), RUN("Run task");
    private final String label;
    AgentAction(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
