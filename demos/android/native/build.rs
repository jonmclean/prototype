fn main() {
    encrypted_spaces_sdk_codegen::compile("../../tauri/app_schema.kdl")
        .expect("Android SDK schema codegen failed");
}
