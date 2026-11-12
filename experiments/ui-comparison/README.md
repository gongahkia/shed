# Archived UI comparison

The bounded Swing/Compose evaluation selected **Swing**. The rejected experimental
Kotlin/Compose code, pinned Gradle bootstrap, renderer dependencies and duplicate Swing
prototype were removed during the production redesign. The default Maven build never
used those dependencies. No experimental framework is shipped in Shed.

The decision, measurements, scope and verification limits remain in
[UI_MODERNIZATION.md](../../docs/UI_MODERNIZATION.md#stage-3--bounded-swingcompose-comparison).
Raw samples and artifact hashes remain in [performance/ui-stage3](../../docs/performance/ui-stage3),
and the [matched screenshots](../../docs/images/ui-comparison) remain available.

Historical source can be inspected in commit `0c3ff946828fa1f25f43d8a06846720b5a9ad954`
under this directory. That commit also contains subsequent production-navigation work;
rebuilding its core does not reproduce the recorded stage-three core artifact. The
recorded comparison used core JAR SHA-256
`c051395ad94d4aa1cbfcc39ddf79b22700091853701e163a9696d5f97fa4d9b1`.
Historical commands in that version of this README describe the original experiment,
not supported launch commands for the current checkout.

Reusable PID-scoped window capture and accessibility-bridge helpers were retained under
`src/benchmark/java/shed` for the production journey probes. Use the normal Maven build
and production gallery/benchmark instructions for current Shed.
