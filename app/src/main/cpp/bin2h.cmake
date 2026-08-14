# Converts SPV_INPUT into a C header (OUT_HEADER) declaring
# `static const unsigned char <ARRAY_NAME>[]` and `<ARRAY_NAME>_len`.
# Invoked as a CMake script (cmake -P bin2h.cmake -DSPV_INPUT=... -DOUT_HEADER=... -DARRAY_NAME=...)
# rather than a project-level include, so it runs at build time with
# up-to-date variables each time the .comp source changes.

if(NOT DEFINED SPV_INPUT OR NOT DEFINED OUT_HEADER OR NOT DEFINED ARRAY_NAME)
    message(FATAL_ERROR "bin2h.cmake requires -DSPV_INPUT=, -DOUT_HEADER=, -DARRAY_NAME=")
endif()

file(READ "${SPV_INPUT}" hex_content HEX)
string(LENGTH "${hex_content}" hex_length)
math(EXPR byte_count "${hex_length} / 2")

set(output "// Auto-generated at build time from ${SPV_INPUT} - do not edit.\n")
string(APPEND output "static const unsigned char ${ARRAY_NAME}[] = {\n")

set(pos 0)
set(col 0)
while(pos LESS hex_length)
    string(SUBSTRING "${hex_content}" ${pos} 2 byte_hex)
    string(APPEND output "0x${byte_hex},")
    math(EXPR pos "${pos} + 2")
    math(EXPR col "${col} + 1")
    if(col GREATER_EQUAL 16)
        string(APPEND output "\n")
        set(col 0)
    endif()
endwhile()

string(APPEND output "\n};\n")
string(APPEND output "static const unsigned int ${ARRAY_NAME}_len = ${byte_count};\n")

file(WRITE "${OUT_HEADER}" "${output}")
