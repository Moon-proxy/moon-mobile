/*
 * MoonMobile bridge: the C entry points of libmoonmobile (rust/src/ffi.rs).
 *
 * Every function takes and returns JSON text (shapes in rust/src/api.rs). A reply is a string
 * the bridge allocated: read it, then pass it to mm_free exactly once. Requests are
 * NUL-terminated UTF-8 and only need to live for the duration of the call.
 */
#ifndef MOONMOBILE_H
#define MOONMOBILE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Opens the configuration and starts the engine. Blocks for about a second: not on the UI thread. */
char *mm_open(const char *request);

/* Runs one command on the engine and waits for its answer. */
char *mm_call(const char *request);

/* A request that needs no engine: the app's section of settings.toml. */
char *mm_tool(const char *request);

/* The latest state; with `since` equal to the current revision only says "unchanged". */
char *mm_snapshot(int64_t since);

/* Draws one chart frame into `pixels`: `len` bytes of premultiplied RGBA (width * height * 4). */
char *mm_chart(const char *request, uint8_t *pixels, size_t len);

/* Stops the engine and every core connection. */
void mm_close(void);

bool mm_is_open(void);

/* The bridge's own version. */
char *mm_version(void);

/* Frees a reply of any function above. NULL is ignored. */
void mm_free(char *text);

#ifdef __cplusplus
}
#endif

#endif /* MOONMOBILE_H */
