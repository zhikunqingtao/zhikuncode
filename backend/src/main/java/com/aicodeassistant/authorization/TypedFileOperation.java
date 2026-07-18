package com.aicodeassistant.authorization;

/** 封闭的文件能力操作枚举；绝不能根据任意工具文本推断这些值。 */
public enum TypedFileOperation {
    READ_FILE, LIST_DIRECTORY, CREATE_FILE, PATCH_FILE, REPLACE_FILE, DELETE_FILE
}
