import Metal
@testable import ItsyRender
import Testing

private struct TestGlyphInstance {
	var screenOrigin: SIMD2<Float>
	var size: SIMD2<Float>
	var atlasUV: SIMD4<Float>
	var color: SIMD4<Float>
}

private struct TestViewportUniforms {
	var size: SIMD2<Float>
}

private struct TestFragmentUniforms {
	var atlasMode: UInt32
}

@Test func shadersRenderInstancedGlyphQuad() throws {
	let device = try #require(MTLCreateSystemDefaultDevice())
	let queue = try #require(device.makeCommandQueue())
	let library = try device.makeLibrary(source: ShaderSource.load(), options: nil)
	let descriptor = MTLRenderPipelineDescriptor()
	descriptor.vertexFunction = library.makeFunction(name: "glyph_vertex")
	descriptor.fragmentFunction = library.makeFunction(name: "glyph_fragment")
	descriptor.colorAttachments[0].pixelFormat = .bgra8Unorm
	let pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
	let atlasDescriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .r8Unorm, width: 1, height: 1, mipmapped: false)
	atlasDescriptor.usage = [.shaderRead]
	let atlas = try #require(device.makeTexture(descriptor: atlasDescriptor))
	var coverage: UInt8 = 255
	atlas.replace(region: MTLRegionMake2D(0, 0, 1, 1), mipmapLevel: 0, withBytes: &coverage, bytesPerRow: 1)
	let targetDescriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: 32, height: 32, mipmapped: false)
	targetDescriptor.usage = [.renderTarget]
	let target = try #require(device.makeTexture(descriptor: targetDescriptor))
	var instance = TestGlyphInstance(
		screenOrigin: SIMD2<Float>(4, 4),
		size: SIMD2<Float>(16, 16),
		atlasUV: SIMD4<Float>(0, 0, 1, 1),
		color: SIMD4<Float>(1, 0, 0, 1)
	)
	var viewport = TestViewportUniforms(size: SIMD2<Float>(32, 32))
	let samplerDescriptor = MTLSamplerDescriptor()
	samplerDescriptor.minFilter = .nearest
	samplerDescriptor.magFilter = .nearest
	let sampler = try #require(device.makeSamplerState(descriptor: samplerDescriptor))
	var fragment = TestFragmentUniforms(atlasMode: 1)
	let pass = MTLRenderPassDescriptor()
	pass.colorAttachments[0].texture = target
	pass.colorAttachments[0].loadAction = .clear
	pass.colorAttachments[0].storeAction = .store
	pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
	let commandBuffer = try #require(queue.makeCommandBuffer())
	let encoder = try #require(commandBuffer.makeRenderCommandEncoder(descriptor: pass))
	encoder.setRenderPipelineState(pipeline)
	encoder.setVertexBytes(&instance, length: MemoryLayout<TestGlyphInstance>.stride, index: 0)
	encoder.setVertexBytes(&viewport, length: MemoryLayout<TestViewportUniforms>.stride, index: 1)
	encoder.setFragmentTexture(atlas, index: 0)
	encoder.setFragmentSamplerState(sampler, index: 0)
	encoder.setFragmentBytes(&fragment, length: MemoryLayout<TestFragmentUniforms>.stride, index: 0)
	encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6, instanceCount: 1)
	encoder.endEncoding()
	commandBuffer.commit()
	commandBuffer.waitUntilCompleted()
	var pixels = [UInt8](repeating: 0, count: 32 * 32 * 4)
	target.getBytes(&pixels, bytesPerRow: 32 * 4, from: MTLRegionMake2D(0, 0, 32, 32), mipmapLevel: 0)
	let litPixelExists = stride(from: 0, to: pixels.count, by: 4).contains { offset in
		pixels[offset + 2] > 0 && pixels[offset + 3] > 0
	}
	#expect(litPixelExists)
}

@Test func shadersRenderSolidColorBranch() throws {
	let device = try #require(MTLCreateSystemDefaultDevice())
	let queue = try #require(device.makeCommandQueue())
	let library = try device.makeLibrary(source: ShaderSource.load(), options: nil)
	let descriptor = MTLRenderPipelineDescriptor()
	descriptor.vertexFunction = library.makeFunction(name: "glyph_vertex")
	descriptor.fragmentFunction = library.makeFunction(name: "glyph_fragment")
	descriptor.colorAttachments[0].pixelFormat = .bgra8Unorm
	let pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
	let atlasDescriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .r8Unorm, width: 1, height: 1, mipmapped: false)
	atlasDescriptor.usage = [.shaderRead]
	let atlas = try #require(device.makeTexture(descriptor: atlasDescriptor))
	var coverage: UInt8 = 0
	atlas.replace(region: MTLRegionMake2D(0, 0, 1, 1), mipmapLevel: 0, withBytes: &coverage, bytesPerRow: 1)
	let targetDescriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: 16, height: 16, mipmapped: false)
	targetDescriptor.usage = [.renderTarget]
	let target = try #require(device.makeTexture(descriptor: targetDescriptor))
	var instance = TestGlyphInstance(
		screenOrigin: SIMD2<Float>(2, 2),
		size: SIMD2<Float>(8, 8),
		atlasUV: SIMD4<Float>(0, 0, 1, 1),
		color: SIMD4<Float>(0, 1, 0, 1)
	)
	var viewport = TestViewportUniforms(size: SIMD2<Float>(16, 16))
	var fragment = TestFragmentUniforms(atlasMode: 0)
	let sampler = try #require(device.makeSamplerState(descriptor: MTLSamplerDescriptor()))
	let pass = MTLRenderPassDescriptor()
	pass.colorAttachments[0].texture = target
	pass.colorAttachments[0].loadAction = .clear
	pass.colorAttachments[0].storeAction = .store
	pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
	let commandBuffer = try #require(queue.makeCommandBuffer())
	let encoder = try #require(commandBuffer.makeRenderCommandEncoder(descriptor: pass))
	encoder.setRenderPipelineState(pipeline)
	encoder.setVertexBytes(&instance, length: MemoryLayout<TestGlyphInstance>.stride, index: 0)
	encoder.setVertexBytes(&viewport, length: MemoryLayout<TestViewportUniforms>.stride, index: 1)
	encoder.setFragmentTexture(atlas, index: 0)
	encoder.setFragmentSamplerState(sampler, index: 0)
	encoder.setFragmentBytes(&fragment, length: MemoryLayout<TestFragmentUniforms>.stride, index: 0)
	encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6, instanceCount: 1)
	encoder.endEncoding()
	commandBuffer.commit()
	commandBuffer.waitUntilCompleted()
	var pixels = [UInt8](repeating: 0, count: 16 * 16 * 4)
	target.getBytes(&pixels, bytesPerRow: 16 * 4, from: MTLRegionMake2D(0, 0, 16, 16), mipmapLevel: 0)
	let litPixelExists = stride(from: 0, to: pixels.count, by: 4).contains { offset in
		pixels[offset + 1] > 0 && pixels[offset + 3] > 0
	}
	#expect(litPixelExists)
}

@Test func shadersRenderSubpixelAtlasCoverage() throws {
	let device = try #require(MTLCreateSystemDefaultDevice())
	let queue = try #require(device.makeCommandQueue())
	let library = try device.makeLibrary(source: ShaderSource.load(), options: nil)
	let descriptor = MTLRenderPipelineDescriptor()
	descriptor.vertexFunction = library.makeFunction(name: "glyph_vertex")
	descriptor.fragmentFunction = library.makeFunction(name: "glyph_fragment")
	descriptor.colorAttachments[0].pixelFormat = .bgra8Unorm
	let pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
	let atlasDescriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: 1, height: 1, mipmapped: false)
	atlasDescriptor.usage = [.shaderRead]
	let atlas = try #require(device.makeTexture(descriptor: atlasDescriptor))
	var coverage: [UInt8] = [255, 0, 0, 255]
	atlas.replace(region: MTLRegionMake2D(0, 0, 1, 1), mipmapLevel: 0, withBytes: &coverage, bytesPerRow: 4)
	let targetDescriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: 8, height: 8, mipmapped: false)
	targetDescriptor.usage = [.renderTarget]
	let target = try #require(device.makeTexture(descriptor: targetDescriptor))
	var instance = TestGlyphInstance(
		screenOrigin: SIMD2<Float>(0, 0),
		size: SIMD2<Float>(8, 8),
		atlasUV: SIMD4<Float>(0, 0, 1, 1),
		color: SIMD4<Float>(1, 1, 1, 1)
	)
	var viewport = TestViewportUniforms(size: SIMD2<Float>(8, 8))
	var fragment = TestFragmentUniforms(atlasMode: 2)
	let sampler = try #require(device.makeSamplerState(descriptor: MTLSamplerDescriptor()))
	let pass = MTLRenderPassDescriptor()
	pass.colorAttachments[0].texture = target
	pass.colorAttachments[0].loadAction = .clear
	pass.colorAttachments[0].storeAction = .store
	pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
	let commandBuffer = try #require(queue.makeCommandBuffer())
	let encoder = try #require(commandBuffer.makeRenderCommandEncoder(descriptor: pass))
	encoder.setRenderPipelineState(pipeline)
	encoder.setVertexBytes(&instance, length: MemoryLayout<TestGlyphInstance>.stride, index: 0)
	encoder.setVertexBytes(&viewport, length: MemoryLayout<TestViewportUniforms>.stride, index: 1)
	encoder.setFragmentTexture(atlas, index: 0)
	encoder.setFragmentSamplerState(sampler, index: 0)
	encoder.setFragmentBytes(&fragment, length: MemoryLayout<TestFragmentUniforms>.stride, index: 0)
	encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6, instanceCount: 1)
	encoder.endEncoding()
	commandBuffer.commit()
	commandBuffer.waitUntilCompleted()
	var pixels = [UInt8](repeating: 0, count: 8 * 8 * 4)
	target.getBytes(&pixels, bytesPerRow: 8 * 4, from: MTLRegionMake2D(0, 0, 8, 8), mipmapLevel: 0)
	let redOnlyPixelExists = stride(from: 0, to: pixels.count, by: 4).contains { offset in
		pixels[offset + 2] > 0 && pixels[offset + 1] == 0 && pixels[offset] == 0 && pixels[offset + 3] > 0
	}
	#expect(redOnlyPixelExists)
}
