// Passthrough vertex stage, mirroring vanilla's own blit.vsh.
//
// Why ship it instead of reusing "minecraft:blit": the vertex field in the program JSON resolves
// against *that file's* sampler/uniform list, and the two extra uniforms this program declares
// (Desaturate, Lift) have to survive into the fragment stage. Keeping our own vertex stage means
// the uniform set is described by one file rather than by a guess about what vanilla's does, and it
// costs 12 lines.
//
// Quad semantics: Position.xy is the 0..1 fullscreen triangle-strip position, Position.zw is the
// framebuffer the pass read from — the same convention every vanilla post pass uses.
#version 120

uniform mat4 ProjMat;
uniform vec2 OutSize;

attribute vec4 Position;

varying vec2 texCoord;

void main() {
	vec4 outPos = ProjMat * vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
	gl_Position = vec4(outPos.xy, 0.2, 1.0);
	texCoord = Position.zw;
}
