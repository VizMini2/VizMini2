// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

/* Stub: clang only uses it to know whether the terminal supports colors. */
int setupterm(const char *t, int fd, int *err) { if (err) *err = 0; return -1; }
int tigetnum(const char *c) { return -2; }
void *set_curterm(void *t) { return 0; }
int del_curterm(void *t) { return 0; }
