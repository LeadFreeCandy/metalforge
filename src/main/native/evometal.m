// MetalForge additions to mcopt's native Metal backend (mcmetal.m): window setup for MC 26.1.2's GLFW window.
// 26.3 (mcopt's target) gets its CAMetalLayer from SDL; on 26.1.2 + NeoForge the window comes from GLFW, possibly taken over from
// NeoForge's early loading screen with an NSOpenGLContext attached. We detach that context's drawable (the context stays usable,
// so stray GL calls from mods render nowhere instead of crashing) and give the content view a CAMetalLayer.
#import <Cocoa/Cocoa.h>
#import <QuartzCore/CAMetalLayer.h>

// Returns a retained CAMetalLayer installed as the view's backing layer. Must run on the main (AppKit) thread.
long evo_layer_attach(long nsviewPtr) {
	@autoreleasepool {
		NSView *view = (NSView *) nsviewPtr;
		NSOpenGLContext *gl = [NSOpenGLContext currentContext];
		if (gl != nil) [gl clearDrawable];
		CAMetalLayer *layer = [[CAMetalLayer layer] retain];
		[view setWantsLayer:YES];
		[view setLayer:layer];
		CGFloat scale = view.window != nil ? view.window.backingScaleFactor : 2.0;
		layer.contentsScale = scale;
		layer.opaque = YES;
		return (long) layer;
	}
}

// Keep the layer's backing scale in sync (moving between displays).
void evo_layer_scale(long layerPtr, long nsviewPtr) {
	CAMetalLayer *layer = (CAMetalLayer *) layerPtr;
	NSView *view = (NSView *) nsviewPtr;
	if (view.window != nil) layer.contentsScale = view.window.backingScaleFactor;
}

// RenderPass.setViewport (26.1.2 has it; 26.3's renderpearl uses a render area instead, so mcopt has no native for it).
#import "sdk14compat.h"
#import "mcmetal.h"
void evo_r_viewport(Enc *enc, int x, int y, int w, int h) {
	if (!enc->render) return;
	[enc->render setViewport:(MTLViewport) {x, y, w, h, 0.0, 1.0}];
}

// The CGDirectDisplayID of the screen the view's window is on (0 if unknown).
unsigned evo_view_display(long nsviewPtr) {
	NSView *view = (NSView *) nsviewPtr;
	NSScreen *screen = view.window.screen;
	if (screen == nil) return 0;
	NSNumber *n = screen.deviceDescription[@"NSScreenNumber"];
	return n ? n.unsignedIntValue : 0;
}
