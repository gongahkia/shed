package shed;

record WorkspaceSearchQuery(SearchPattern pattern, String include, String exclude) {
    WorkspaceSearchQuery {
        java.util.Objects.requireNonNull(pattern, "pattern");
        include = include == null ? "" : include;
        exclude = exclude == null ? "" : exclude;
        pattern.compile();
        new SearchFileFilter(include, exclude);
    }
    static WorkspaceSearchQuery literal(String query) { return new WorkspaceSearchQuery(SearchPattern.literal(query), "", ""); }
}
