# Cloud PC engine – third-party programs

`app/src/main/jniLibs/arm64-v8a/` contains unmodified Termux builds (fetched by
`tools/fetch-proot.sh` and verified against the Termux repository index):

| File in the APK | Program | Licence | Source |
|---|---|---|---|
| `libproot.so`, `libproot-loader.so` | PRoot 5.1.107 (Termux build) | GPL-2.0 | https://github.com/termux/proot · build recipe: https://github.com/termux/termux-packages/tree/master/packages/proot |
| `libtalloc.so` | talloc 2.5.0 | LGPL-3.0 | https://talloc.samba.org · https://github.com/termux/termux-packages/tree/master/packages/libtalloc |
| `libandroid-shmem.so` | libandroid-shmem 0.7 | BSD-3-Clause | https://github.com/termux/libandroid-shmem |
| `libvirgl_server.so`, `libvirglrenderer.so`, `libepoxy.so` | virglrenderer-android 1.3.0 (Termux build; `tools/fetch-virgl.sh`) | MIT (virglrenderer, libepoxy, gl4es) | https://gitlab.freedesktop.org/virgl/virglrenderer · https://github.com/termux/termux-packages |

They are separate programs that AirCursor starts; they are not linked into AirCursor's own code.
On request we provide the exact source code of these versions (write to the repository owner).

The Ubuntu files are downloaded by the app at install time from
https://cdimage.ubuntu.com/ubuntu-base/ (Ubuntu is a trademark of Canonical Ltd.).
