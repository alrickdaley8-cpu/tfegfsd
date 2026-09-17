// Doomsday Nukes — post detonation desaturation.
//
// What this pass does and does not do
// -----------------------------------
// It does exactly one thing the HUD cannot: change the *colour response* of every pixel in the
// world, flattening it toward luminance while lifting the floor so shadows go milky. That is the
// "the colour has gone out of the world" look of the seconds after a flash, and no amount of
// translucent quads on top of the screen can produce it, because quads add light — they cannot
// subtract saturation from what is already drawn.
//
// It deliberately does NOT fade to white or back. The whiteout envelope, the afterimage bloom and
// the recovery are all HUD quads, driven per-frame from the same stage timer that drives the
// geometry, because a shader in this pipeline is a fixed function of the framebuffer: getting a
// smooth 0.2 s ramp out of it means either a uniform that the mod cannot set (see the note in
// PostPipelineBridge) or rebuilding the pipeline every frame. Two effects that each do what they
// are good at beat one that does both badly.
//
// The numbers: luminance uses the Rec. 709 weights rather than the 0.3/0.59/0.11 average, because
// the sky in Minecraft is mostly blue and the older weights leave it far too bright after the
// desaturation, which reads as a bug rather than as a bleached horizon.
#version 120

uniform sampler2D DiffuseSampler;
uniform float Desaturate;
uniform float Lift;

varying vec2 texCoord;

void main() {
	vec4 src = texture2D(DiffuseSampler, texCoord);
	float lum = dot(src.rgb, vec3(0.2126, 0.7152, 0.0722));
	vec3 flat = vec3(lum);
	// Lifting toward grey, never toward white: white here would fight the HUD's own whiteout quad
	// and double the perceived brightness for no gain.
	vec3 outColor = mix(src.rgb, flat, clamp(Desaturate, 0.0, 1.0));
	outColor = outColor + vec3(Lift) * (1.0 - outColor);
	gl_FragColor = vec4(clamp(outColor, 0.0, 1.0), src.a);
}
