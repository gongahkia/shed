import CryptoKit
import Foundation
@testable import ItsyEditor
import Testing

@Test func signedCatalogVerificationAcceptsCompatibleSignedPayload() throws {
	let key = Curve25519.Signing.PrivateKey()
	let component = try #require(ManagedSupportCatalog.bundled.component(id: "pyright"))
	let payload = try JSONEncoder().encode(ManagedSupportCatalog(components: [component]))
	let envelope = SignedManagedSupportCatalog(payload: payload, signature: try key.signature(for: payload))
	let data = try JSONEncoder().encode(envelope)
	let catalog = try ManagedSupportCatalogUpdateClient.verify(data, publicKey: key.publicKey.rawRepresentation)
	#expect(catalog.components == [component])
}

@Test func signedCatalogVerificationRejectsTamperedPayload() throws {
	let key = Curve25519.Signing.PrivateKey()
	let payload = try JSONEncoder().encode(ManagedSupportCatalog(components: []))
	let envelope = SignedManagedSupportCatalog(payload: payload, signature: try key.signature(for: payload))
	let data = try JSONEncoder().encode(SignedManagedSupportCatalog(payload: Data("tampered".utf8), signature: envelope.signature))
	#expect(throws: ManagedSupportCatalogUpdateError.invalidSignature) {
		try ManagedSupportCatalogUpdateClient.verify(data, publicKey: key.publicKey.rawRepresentation)
	}
}

@Test func releaseCatalogSourceIsCompatible() throws {
	let root = URL(fileURLWithPath: #filePath)
		.deletingLastPathComponent()
		.deletingLastPathComponent()
		.deletingLastPathComponent()
	let data = try Data(contentsOf: root.appendingPathComponent("docs/lsp-catalog.json"))
	let catalog = try JSONDecoder().decode(ManagedSupportCatalog.self, from: data)
	try ManagedSupportCatalogStore.validate(catalog)
	#expect(Set(catalog.components.map(\.id)) == ["pyright", "typescript-language-server"])
}
