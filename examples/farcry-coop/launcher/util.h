/* FarCryCoop launcher: logging and small path helpers (Win32, ANSI). */
#ifndef FARCRY_COOP_UTIL_H
#define FARCRY_COOP_UTIL_H

#include <windows.h>

#define FCC_PATH 1024

/* Log file next to FarCryCoop.exe (FarCryCoop.log). Also echoes to the console when one is attached and
 * to the GUI log callback when set. */
void log_init(void);
void log_msg(const char *fmt, ...);
void log_set_sink(void (*sink)(const char *line));
const char *log_path(void);

/* Directory of FarCryCoop.exe, without trailing backslash. */
const char *exe_dir(void);

int path_join(char *out, size_t size, const char *a, const char *b);
int file_exists(const char *path);
int dir_exists(const char *path);

#endif
