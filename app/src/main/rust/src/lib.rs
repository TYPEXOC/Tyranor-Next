use std::io::{Cursor, Read};

use encoding_rs::SHIFT_JIS;
use jni::JNIEnv;
use jni::objects::{JByteArray, JObject, JString};
use jni::sys::jbyteArray;
#[cfg(test)]
use rpgmad_lib::ArchiveEntry;
use rpgmad_lib::{Engine as ArchiveEngine, decrypt_from_reader};
use rvpacker_txt_rs_lib::json;
use serde_json::{Value, json};

const MAX_INPUT_BYTES: usize = 64 * 1024 * 1024;
const MAX_RESOURCE_BYTES: usize = 32 * 1024 * 1024;
const MAX_OUTPUT_BYTES: usize = 64 * 1024 * 1024;
const MAX_ARCHIVE_ENTRIES: usize = 50_000;

const ERROR_CORRUPT: &[u8] = b"!CORRUPT_RESOURCE";
const ERROR_TOO_LARGE: &[u8] = b"!RESOURCE_TOO_LARGE";
const ERROR_UNSUPPORTED: &[u8] = b"!UNSUPPORTED_FORMAT";

/// Decode one RPG Maker binary resource into a bounded JSON representation.
///
/// RGSS archives are read and decrypted in memory. Only supported files under
/// `Data/` are parsed; archive scripts and assets are ignored. Nothing is
/// written to disk and no Ruby/game code is executed.
fn decode(kind: &str, file_name: &str, input: &[u8]) -> Result<Vec<u8>, &'static [u8]> {
    if input.len() > MAX_INPUT_BYTES {
        return Err(ERROR_TOO_LARGE);
    }

    let output = match kind {
        "RGSS_DATA" => json::generate_file(input, file_name, None)
            .map_err(|_| ERROR_CORRUPT)?
            .into_bytes(),
        "RM2K_DATABASE" => decode_rm2k(json::generate_rm2k_database_file(input))?,
        "RM2K_TREE" => decode_rm2k(json::generate_rm2k_tree_map_file(input))?,
        "RM2K_MAP" => decode_rm2k(json::generate_rm2k_map_file(input))?,
        "RGSS_ARCHIVE" => decode_archive(file_name, input)?,
        _ => return Err(ERROR_UNSUPPORTED),
    };

    if output.len() > MAX_OUTPUT_BYTES {
        return Err(ERROR_TOO_LARGE);
    }
    Ok(output)
}

fn decode_rm2k(json: Result<String, impl std::fmt::Debug>) -> Result<Vec<u8>, &'static [u8]> {
    let json = json.map_err(|_| ERROR_CORRUPT)?;
    let mut document: Value = serde_json::from_str(&json).map_err(|_| ERROR_CORRUPT)?;
    normalize_rm2k_text_fields(&mut document);
    serde_json::to_vec(&document).map_err(|_| ERROR_CORRUPT)
}

/// rm2k-lib preserves LCF strings as byte arrays in its JSON representation.
/// Convert only known text-bearing fields from the RPG Maker 2000/2003 model;
/// numeric parameters and unknown fields remain untouched.
fn normalize_rm2k_text_fields(value: &mut Value) {
    match value {
        Value::Object(fields) => {
            for (name, child) in fields.iter_mut() {
                if is_rm2k_text_field(name) {
                    if let Some(bytes) = child.as_array().and_then(|items| byte_array(items)) {
                        let (text, _, _) = SHIFT_JIS.decode(&bytes);
                        *child = Value::String(text.into_owned());
                        continue;
                    }
                }
                normalize_rm2k_text_fields(child);
            }
        }
        Value::Array(items) => items.iter_mut().for_each(normalize_rm2k_text_fields),
        _ => {}
    }
}

fn byte_array(items: &[Value]) -> Option<Vec<u8>> {
    items
        .iter()
        .map(|item| item.as_u64().and_then(|value| u8::try_from(value).ok()))
        .collect()
}

fn is_rm2k_text_field(field: &str) -> bool {
    matches!(
        field.to_ascii_lowercase().as_str(),
        "string"
            | "name"
            | "title"
            | "description"
            | "nickname"
            | "profile"
            | "message1"
            | "message2"
            | "message3"
            | "message4"
            | "currency_unit"
            | "game_title"
            | "display_name"
            | "using_message1"
            | "using_message2"
    )
}

fn decode_archive(file_name: &str, input: &[u8]) -> Result<Vec<u8>, &'static [u8]> {
    let extension = file_name
        .rsplit_once('.')
        .map(|(_, extension)| extension)
        .unwrap_or_default();
    let expected_data_extension = match extension.to_ascii_lowercase().as_str() {
        "rgssad" => "rxdata",
        "rgss2a" => "rvdata",
        "rgss3a" => "rvdata2",
        _ => return Err(ERROR_UNSUPPORTED),
    };
    if ArchiveEngine::from_extension(extension).is_none() {
        return Err(ERROR_UNSUPPORTED);
    }

    let entry_count = bounded_archive_entry_count(extension, input)?;
    let mut archive = decrypt_from_reader(Cursor::new(input)).map_err(|_| ERROR_CORRUPT)?;
    if archive.entry_count() != entry_count {
        return Err(ERROR_CORRUPT);
    }

    let mut resources = Vec::new();
    let mut output_bytes = 0usize;
    for index in 0..entry_count {
        let Some(path) = decode_archive_path(archive.path(index)) else {
            continue;
        };
        if !is_safe_relative_path(&path) || !is_rgss_data_resource(&path, expected_data_extension) {
            continue;
        }

        let mut content = Vec::new();
        archive
            .read_entry(index)
            .map_err(|_| ERROR_CORRUPT)?
            .take((MAX_RESOURCE_BYTES + 1) as u64)
            .read_to_end(&mut content)
            .map_err(|_| ERROR_CORRUPT)?;
        if content.len() > MAX_RESOURCE_BYTES {
            return Err(ERROR_TOO_LARGE);
        }

        let file_name = path.rsplit('/').next().unwrap_or_default();
        let document = json::generate_file(&content, file_name, None).map_err(|_| ERROR_CORRUPT)?;
        let document: Value = serde_json::from_str(&document).map_err(|_| ERROR_CORRUPT)?;
        output_bytes = output_bytes.saturating_add(content.len());
        if output_bytes > MAX_OUTPUT_BYTES {
            return Err(ERROR_TOO_LARGE);
        }
        resources.push(json!({ "file": path, "document": document }));
    }

    serde_json::to_vec(&json!({ "resources": resources })).map_err(|_| ERROR_CORRUPT)
}

/// Count archive entries before `rpgmad-lib` builds its entry table. That crate's own cap is
/// intentionally much higher than the limit this bridge can safely process in one request.
fn bounded_archive_entry_count(extension: &str, input: &[u8]) -> Result<usize, &'static [u8]> {
    const HEADER_SIZE: usize = 8;
    const VXACE_HEADER_SIZE: usize = 12;

    if input.len() < HEADER_SIZE || &input[..7] != b"RGSSAD\0" {
        return Err(ERROR_CORRUPT);
    }
    let expected_version = if extension.eq_ignore_ascii_case("rgss3a") {
        3
    } else {
        1
    };
    if input[7] != expected_version {
        return Err(ERROR_CORRUPT);
    }

    match extension.to_ascii_lowercase().as_str() {
        "rgssad" | "rgss2a" => {
            let mut position = HEADER_SIZE;
            let mut key = 0xDEAD_CAFEu32;
            let mut count = 0usize;
            while position < input.len() {
                let encrypted_path_size = read_archive_u32(input, position)?;
                let path_size = u64::from(encrypted_path_size ^ key);
                key = step_older_key(key);
                position = position.checked_add(4).ok_or(ERROR_CORRUPT)?;
                let path_size = usize::try_from(path_size).map_err(|_| ERROR_CORRUPT)?;
                let path_end = position.checked_add(path_size).ok_or(ERROR_CORRUPT)?;
                if path_end > input.len() {
                    return Err(ERROR_CORRUPT);
                }
                key = advance_older_key(key, path_size as u64);

                let encrypted_data_size = read_archive_u32(input, path_end)?;
                let data_size = u64::from(encrypted_data_size ^ key);
                key = step_older_key(key);
                let data_start = path_end.checked_add(4).ok_or(ERROR_CORRUPT)?;
                let data_size = usize::try_from(data_size).map_err(|_| ERROR_CORRUPT)?;
                let data_end = data_start.checked_add(data_size).ok_or(ERROR_CORRUPT)?;
                if data_end > input.len() {
                    return Err(ERROR_CORRUPT);
                }

                count += 1;
                if count > MAX_ARCHIVE_ENTRIES {
                    return Err(ERROR_TOO_LARGE);
                }
                position = data_end;
            }
            Ok(count)
        }
        "rgss3a" => {
            if input.len() < VXACE_HEADER_SIZE {
                return Err(ERROR_CORRUPT);
            }
            let seed = read_archive_u32(input, 8)?;
            let key = seed.wrapping_mul(9).wrapping_add(3);
            let mut position = VXACE_HEADER_SIZE;
            let mut count = 0usize;
            loop {
                let encrypted_offset = read_archive_u32(input, position)?;
                if encrypted_offset ^ key == 0 {
                    return Ok(count);
                }
                let header_end = position.checked_add(16).ok_or(ERROR_CORRUPT)?;
                if header_end > input.len() {
                    return Err(ERROR_CORRUPT);
                }
                let path_size = usize::try_from(read_archive_u32(input, position + 12)? ^ key)
                    .map_err(|_| ERROR_CORRUPT)?;
                let next_position = header_end.checked_add(path_size).ok_or(ERROR_CORRUPT)?;
                if next_position > input.len() {
                    return Err(ERROR_CORRUPT);
                }
                count += 1;
                if count > MAX_ARCHIVE_ENTRIES {
                    return Err(ERROR_TOO_LARGE);
                }
                position = next_position;
            }
        }
        _ => Err(ERROR_UNSUPPORTED),
    }
}

fn read_archive_u32(input: &[u8], position: usize) -> Result<u32, &'static [u8]> {
    let end = position.checked_add(4).ok_or(ERROR_CORRUPT)?;
    let bytes: [u8; 4] = input
        .get(position..end)
        .ok_or(ERROR_CORRUPT)?
        .try_into()
        .map_err(|_| ERROR_CORRUPT)?;
    Ok(u32::from_le_bytes(bytes))
}

fn step_older_key(key: u32) -> u32 {
    key.wrapping_mul(7).wrapping_add(3)
}

/// Applies `key = key * 7 + 3` `count` times in logarithmic time.
fn advance_older_key(key: u32, mut count: u64) -> u32 {
    let (mut power_multiplier, mut power_addend) = (7u32, 3u32);
    let (mut result_multiplier, mut result_addend) = (1u32, 0u32);
    while count != 0 {
        if count & 1 != 0 {
            result_addend = power_multiplier
                .wrapping_mul(result_addend)
                .wrapping_add(power_addend);
            result_multiplier = power_multiplier.wrapping_mul(result_multiplier);
        }
        power_addend = power_multiplier
            .wrapping_mul(power_addend)
            .wrapping_add(power_addend);
        power_multiplier = power_multiplier.wrapping_mul(power_multiplier);
        count >>= 1;
    }
    result_multiplier
        .wrapping_mul(key)
        .wrapping_add(result_addend)
}

fn decode_archive_path(raw_path: &[u8]) -> Option<String> {
    let (path, _, had_errors) = SHIFT_JIS.decode(raw_path);
    let path = if had_errors {
        String::from_utf8(raw_path.to_vec()).ok()?
    } else {
        path.into_owned()
    };
    Some(path.replace('\\', "/"))
}

fn is_safe_relative_path(path: &str) -> bool {
    if path.is_empty() || path.starts_with('/') || path.contains(':') || path.contains('\0') {
        return false;
    }
    !path
        .split('/')
        .any(|part| part.is_empty() || part == "." || part == "..")
}

fn is_rgss_data_resource(path: &str, expected_data_extension: &str) -> bool {
    let Some((directory, file_name)) = path.rsplit_once('/') else {
        return false;
    };
    if !directory.eq_ignore_ascii_case("Data") {
        return false;
    }
    let lower = file_name.to_ascii_lowercase();
    let extension = file_name
        .rsplit_once('.')
        .map(|(_, extension)| extension)
        .unwrap_or_default();
    if !extension.eq_ignore_ascii_case(expected_data_extension) {
        return false;
    }
    let supported = [
        "actors",
        "animations",
        "armors",
        "classes",
        "commonevents",
        "enemies",
        "items",
        "mapinfos",
        "skills",
        "states",
        "system",
        "tilesets",
        "troops",
        "weapons",
    ]
    .iter()
    .any(|stem| lower == format!("{stem}.{expected_data_extension}"));
    supported || is_map_resource(file_name, expected_data_extension)
}

fn is_map_resource(file_name: &str, expected_data_extension: &str) -> bool {
    let lower = file_name.to_ascii_lowercase();
    let Some(rest) = lower.strip_prefix("map") else {
        return false;
    };
    let Some((number, extension)) = rest.split_once('.') else {
        return false;
    };
    !number.is_empty()
        && number.bytes().all(|byte| byte.is_ascii_digit())
        && extension == expected_data_extension
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_tyranor_next_core_translation_extraction_nativebridge_NativeRpgMakerBinaryDataDecoder_decodeBinary(
    mut env: JNIEnv,
    _receiver: JObject,
    kind: JString,
    file_name: JString,
    input: JByteArray,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let kind: String = env.get_string(&kind).ok()?.into();
        let file_name: String = env.get_string(&file_name).ok()?.into();
        let bytes = env.convert_byte_array(&input).ok()?;
        Some(decode(&kind, &file_name, &bytes).unwrap_or_else(<[u8]>::to_vec))
    }));
    let output = match result {
        Ok(Some(output)) => output,
        _ => ERROR_CORRUPT.to_vec(),
    };
    match env.byte_array_from_slice(&output) {
        Ok(array) => array.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rpgmad_lib::encrypt_to_vec;

    #[test]
    fn rejects_unknown_formats_and_malformed_binary_resources() {
        assert_eq!(
            decode("unknown", "x", b"data").unwrap_err(),
            ERROR_UNSUPPORTED
        );
        assert_eq!(
            decode("RM2K_DATABASE", "RPG_RT.ldb", b"bad").unwrap_err(),
            ERROR_CORRUPT
        );
    }

    #[test]
    fn rgss_archive_decoder_skips_non_data_files_without_extracting_to_disk() {
        let archive = encrypt_to_vec(
            &[ArchiveEntry {
                path: b"Graphics/Pictures/Test.png",
                data: b"image",
            }],
            ArchiveEngine::Older,
        )
        .expect("synthetic archive should encrypt");
        let output = decode("RGSS_ARCHIVE", "Game.rgssad", &archive).expect("archive should parse");
        let value: Value = serde_json::from_slice(&output).expect("output should be JSON");
        assert_eq!(value["resources"].as_array().map(Vec::len), Some(0));
    }

    #[test]
    fn archive_path_filter_rejects_paths_outside_the_project() {
        assert!(!is_safe_relative_path("../Data/Map001.rxdata"));
        assert!(!is_safe_relative_path("C:/Data/System.rvdata2"));
        assert!(is_safe_relative_path("Data/Map001.rvdata2"));
    }

    #[test]
    fn map_file_filter_is_case_insensitive_and_requires_numeric_suffix() {
        assert!(is_map_resource("Map001.rvdata2", "rvdata2"));
        assert!(!is_map_resource("MapA.rvdata2", "rvdata2"));
        assert!(!is_map_resource("Maps.rxdata", "rxdata"));
    }

    #[test]
    fn rm2k_normalization_decodes_only_known_lcf_text_fields() {
        let mut document = serde_json::json!({
            "name": [0x82, 0xA0],
            "parameters": [0x82, 0xA2],
            "event_commands": [{ "code": 10110, "string": [0x82, 0xA4] }]
        });
        normalize_rm2k_text_fields(&mut document);
        assert_eq!(document["name"], "あ");
        assert_eq!(document["event_commands"][0]["string"], "う");
        assert!(document["parameters"].is_array());
    }
}
