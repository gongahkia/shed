#include <metal_stdlib>
using namespace metal;

struct GlyphInstance {
	float2 screenOrigin;
	float2 size;
	float4 atlasUV;
	float4 color;
};

struct ViewportUniforms {
	float2 size;
};

struct GlyphVertexOut {
	float4 position [[position]];
	float2 atlasUV;
	float4 color;
};

vertex GlyphVertexOut glyph_vertex(
	uint vertexID [[vertex_id]],
	uint instanceID [[instance_id]],
	constant GlyphInstance *instances [[buffer(0)]],
	constant ViewportUniforms &viewport [[buffer(1)]]
) {
	const float2 corners[6] = {
		float2(0.0, 0.0),
		float2(1.0, 0.0),
		float2(0.0, 1.0),
		float2(1.0, 0.0),
		float2(1.0, 1.0),
		float2(0.0, 1.0),
	};
	const GlyphInstance instance = instances[instanceID];
	const float2 corner = corners[vertexID];
	const float2 pixel = instance.screenOrigin + corner * instance.size;
	const float2 clip = float2(
		(pixel.x / viewport.size.x) * 2.0 - 1.0,
		1.0 - (pixel.y / viewport.size.y) * 2.0
	);
	GlyphVertexOut out;
	out.position = float4(clip, 0.0, 1.0);
	out.atlasUV = mix(instance.atlasUV.xy, instance.atlasUV.zw, corner);
	out.color = instance.color;
	return out;
}

fragment half4 glyph_fragment(
	GlyphVertexOut in [[stage_in]],
	texture2d<float, access::sample> atlas [[texture(0)]],
	sampler atlasSampler [[sampler(0)]]
) {
	const float coverage = atlas.sample(atlasSampler, in.atlasUV).r;
	const float4 color = in.color * coverage;
	return half4(color);
}
