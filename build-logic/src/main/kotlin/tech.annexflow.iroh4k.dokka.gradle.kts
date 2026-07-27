plugins {
    id("org.jetbrains.dokka")
}

// API documentation. The public surface is heavily commented — most of it explaining *why* an FFI
// decision was made — so generated docs are the payoff for that rather than an afterthought.
//
// Nothing is configured per target: Dokka reads the source sets the multiplatform plugin
// registered, so a `-Ptargets` subset documents exactly the platforms that were built. The FFI
// plumbing (handles, codecs, the op registry) lives in `internal` and is therefore already absent
// from the output without any visibility filtering here.
dokka {
    moduleName.set(project.name)
}
