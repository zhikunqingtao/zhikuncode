/**
 * SchemaViewer — OpenAPI JSON Schema 递归展示组件
 *
 * 递归渲染 OpenAPI Schema 树结构，支持：
 * - object/array/primitive 类型可视化
 * - $ref 解引用（components.schemas）
 * - 最大 5 层嵌套深度
 * - 字段类型 badge、required 标记、enum 值列表
 * - 展开/收起交互
 */

import React, { useState, useMemo, useCallback } from 'react';
import { ChevronRight, ChevronDown } from 'lucide-react';
import type { SchemaObject } from '@/store/apiContractStore';

interface SchemaViewerProps {
    schema: SchemaObject;
    name?: string;
    required?: boolean;
    depth?: number;
    allSchemas?: Record<string, SchemaObject>;
}

const MAX_DEPTH = 5;

/** 类型 → badge 颜色 */
const TYPE_COLORS: Record<string, string> = {
    string:  'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400',
    integer: 'bg-blue-500/15 text-blue-600 dark:text-blue-400',
    number:  'bg-blue-500/15 text-blue-600 dark:text-blue-400',
    boolean: 'bg-purple-500/15 text-purple-600 dark:text-purple-400',
    array:   'bg-orange-500/15 text-orange-600 dark:text-orange-400',
    object:  'bg-gray-500/15 text-gray-600 dark:text-gray-400',
};

/** 解引用 $ref */
function resolveRef(schema: SchemaObject, allSchemas?: Record<string, SchemaObject>): SchemaObject {
    if (!schema.$ref || !allSchemas) return schema;
    // $ref: "#/components/schemas/Xxx"
    const refName = schema.$ref.replace('#/components/schemas/', '');
    return allSchemas[refName] ?? schema;
}

/** 获取展示类型名称 */
function getTypeName(schema: SchemaObject): string {
    if (schema.$ref) {
        return schema.$ref.replace('#/components/schemas/', '');
    }
    if (schema.type === 'array' && schema.items) {
        const itemType = schema.items.$ref
            ? schema.items.$ref.replace('#/components/schemas/', '')
            : schema.items.type ?? 'any';
        return `${itemType}[]`;
    }
    if (schema.allOf) return 'allOf';
    if (schema.oneOf) return 'oneOf';
    if (schema.anyOf) return 'anyOf';
    return schema.type ?? 'any';
}

/** 判断是否可展开 */
function isExpandable(schema: SchemaObject, allSchemas?: Record<string, SchemaObject>): boolean {
    const resolved = resolveRef(schema, allSchemas);
    if (resolved.type === 'object' && resolved.properties) return true;
    if (resolved.type === 'array' && resolved.items) {
        const itemResolved = resolveRef(resolved.items, allSchemas);
        return itemResolved.type === 'object' && !!itemResolved.properties;
    }
    if (resolved.allOf || resolved.oneOf || resolved.anyOf) return true;
    // $ref 解引用后可能是 object
    if (schema.$ref && allSchemas) {
        const refResolved = resolveRef(schema, allSchemas);
        return refResolved.type === 'object' && !!refResolved.properties;
    }
    return false;
}

/** 类型 Badge */
const TypeBadge: React.FC<{ typeName: string }> = ({ typeName }) => {
    const baseType = typeName.replace('[]', '').toLowerCase();
    const colorClass = TYPE_COLORS[baseType] ?? TYPE_COLORS.object;
    return (
        <span className={`inline-flex px-1.5 py-0.5 rounded text-[10px] font-mono font-medium ${colorClass}`}>
            {typeName}
        </span>
    );
};

/** 合并 allOf/oneOf/anyOf 为可展示的属性 */
function mergeCompositeSchema(
    schemas: SchemaObject[] | undefined,
    allSchemas?: Record<string, SchemaObject>,
): SchemaObject | null {
    if (!schemas || schemas.length === 0) return null;
    const merged: SchemaObject = { type: 'object', properties: {}, required: [] };
    for (const s of schemas) {
        const resolved = resolveRef(s, allSchemas);
        if (resolved.properties) {
            merged.properties = { ...merged.properties, ...resolved.properties };
        }
        if (resolved.required) {
            merged.required = [...(merged.required ?? []), ...resolved.required];
        }
    }
    return merged;
}

const SchemaViewer: React.FC<SchemaViewerProps> = ({
    schema,
    name,
    required: isRequired = false,
    depth = 0,
    allSchemas,
}) => {
    const [expanded, setExpanded] = useState(depth < 2);

    const resolved = useMemo(() => resolveRef(schema, allSchemas), [schema, allSchemas]);
    const typeName = useMemo(() => getTypeName(schema), [schema]);
    const expandable = useMemo(() => isExpandable(schema, allSchemas), [schema, allSchemas]);

    const toggleExpand = useCallback(() => setExpanded(e => !e), []);

    // 超过最大深度
    if (depth > MAX_DEPTH) {
        return (
            <div className="flex items-center gap-1.5 py-0.5" style={{ paddingLeft: depth * 16 }}>
                {name && <span className="font-medium text-xs text-[var(--text-primary)]">{name}</span>}
                <span className="text-xs text-[var(--text-muted)] italic">... (max depth)</span>
            </div>
        );
    }

    // 获取子属性（object properties 或 array items 或 composite）
    const childSchema = useMemo(() => {
        if (resolved.type === 'object' && resolved.properties) return resolved;
        if (resolved.type === 'array' && resolved.items) {
            const itemResolved = resolveRef(resolved.items, allSchemas);
            if (itemResolved.type === 'object' && itemResolved.properties) return itemResolved;
        }
        if (resolved.allOf) return mergeCompositeSchema(resolved.allOf, allSchemas);
        if (resolved.oneOf) return mergeCompositeSchema(resolved.oneOf, allSchemas);
        if (resolved.anyOf) return mergeCompositeSchema(resolved.anyOf, allSchemas);
        return null;
    }, [resolved, allSchemas]);

    return (
        <div>
            {/* 当前字段行 */}
            <div
                className={`flex items-center gap-1.5 py-1 ${expandable ? 'cursor-pointer hover:bg-[var(--bg-hover)] rounded' : ''}`}
                style={{ paddingLeft: depth * 16 }}
                onClick={expandable ? toggleExpand : undefined}
            >
                {/* 展开/收起箭头 */}
                {expandable ? (
                    <span className="shrink-0 text-[var(--text-muted)]">
                        {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                    </span>
                ) : (
                    <span className="shrink-0 w-[14px]" />
                )}

                {/* 字段名 */}
                {name && (
                    <span className="font-semibold text-xs text-[var(--text-primary)]">
                        {name}
                        {isRequired && <span className="text-red-500 ml-0.5">*</span>}
                    </span>
                )}

                {/* 类型 Badge */}
                <TypeBadge typeName={typeName} />

                {/* Format */}
                {resolved.format && (
                    <span className="text-[10px] text-[var(--text-muted)] italic">
                        ({resolved.format})
                    </span>
                )}

                {/* Deprecated */}
                {resolved.nullable && (
                    <span className="text-[10px] text-yellow-500">nullable</span>
                )}
            </div>

            {/* Description */}
            {resolved.description && (
                <div className="text-[11px] text-[var(--text-muted)] leading-tight pb-0.5" style={{ paddingLeft: depth * 16 + 28 }}>
                    {resolved.description}
                </div>
            )}

            {/* Enum values */}
            {resolved.enum && resolved.enum.length > 0 && (
                <div className="flex flex-wrap gap-1 pb-0.5" style={{ paddingLeft: depth * 16 + 28 }}>
                    <span className="text-[10px] text-[var(--text-muted)]">enum:</span>
                    {resolved.enum.map((v, i) => (
                        <span
                            key={i}
                            className="inline-flex px-1.5 py-0.5 rounded bg-[var(--bg-secondary)] text-[10px] font-mono text-[var(--text-secondary)]"
                        >
                            {String(v)}
                        </span>
                    ))}
                </div>
            )}

            {/* Default value */}
            {resolved.default !== undefined && (
                <div className="text-[10px] text-[var(--text-muted)] pb-0.5" style={{ paddingLeft: depth * 16 + 28 }}>
                    default: <code className="font-mono bg-[var(--bg-secondary)] px-1 rounded">{JSON.stringify(resolved.default)}</code>
                </div>
            )}

            {/* 子属性展开 */}
            {expanded && expandable && childSchema?.properties && (
                <div>
                    {Object.entries(childSchema.properties).map(([propName, propSchema]) => (
                        <SchemaViewer
                            key={propName}
                            schema={propSchema}
                            name={propName}
                            required={childSchema.required?.includes(propName)}
                            depth={depth + 1}
                            allSchemas={allSchemas}
                        />
                    ))}
                </div>
            )}
        </div>
    );
};

export default React.memo(SchemaViewer);
