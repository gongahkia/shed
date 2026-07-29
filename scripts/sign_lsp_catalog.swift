#!/usr/bin/swift
import CryptoKit
import Foundation

struct Envelope: Encodable {
	let schemaVersion: Int
	let payload: Data
	let signature: Data

	private enum CodingKeys: String, CodingKey {
		case schemaVersion = "schema_version"
		case payload
		case signature
	}
}

let arguments = CommandLine.arguments
guard arguments.count == 3 else {
	fputs("usage: sign_lsp_catalog.swift <catalog.json> <signed-catalog.json>\n", stderr)
	exit(2)
}
guard let encodedKey = ProcessInfo.processInfo.environment["ITSY_LSP_CATALOG_PRIVATE_KEY"],
	let privateKeyData = Data(base64Encoded: encodedKey), privateKeyData.count == 32
else {
	fputs("ITSY_LSP_CATALOG_PRIVATE_KEY must be a base64-encoded 32-byte Ed25519 private key seed\n", stderr)
	exit(2)
}

do {
	let payload = try Data(contentsOf: URL(fileURLWithPath: arguments[1]))
	_ = try JSONSerialization.jsonObject(with: payload)
	let privateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: privateKeyData)
	let envelope = Envelope(schemaVersion: 1, payload: payload, signature: try privateKey.signature(for: payload))
	let output = try JSONEncoder().encode(envelope)
	try output.write(to: URL(fileURLWithPath: arguments[2]), options: .atomic)
} catch {
	fputs("failed to sign LSP catalog: \(error)\n", stderr)
	exit(1)
}
