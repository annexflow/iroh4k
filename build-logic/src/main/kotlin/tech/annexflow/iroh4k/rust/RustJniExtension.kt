package tech.annexflow.iroh4k.rust

open class RustJniExtension {
    /** Rust crate name (snake_case), e.g. `iroh4k`. Required. */
    var crateName: String = ""

    /** Project-relative path to the directory containing `Cargo.toml`. */
    var cargoDir: String = "src/rust"
}
