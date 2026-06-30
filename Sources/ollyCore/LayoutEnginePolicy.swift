public enum LayoutEnginePolicy {
    public static func resolvedEngineID(
        activeTags: TagSet,
        tagToEngine: [Tag: LayoutEngineID]
    ) -> LayoutEngineID? {
        activeTags.tags.compactMap { tagToEngine[$0] }.first // lowest active tag-index wins
    }
}
