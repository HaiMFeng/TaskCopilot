// TaskCopilot 前端公共工具层（纯函数与常量，不依赖 Vue setup 上下文）。
// 由 index.html 在 app.js 之前通过 <script> 顺序加载，挂载到 window 供 app.js 引用。

// 前端 JS 版本号（修改后请同步递增，便于辨识加载版本）
window.APP_JS_VERSION = '20260806.1';

/** 任务顶级字段（不放进 config，提交时提升到 payload 顶层） */
window.TOP_LEVEL_FIELDS = new Set(['command', 'workingDir', 'timeoutSeconds']);

window.fmtTime = function fmtTime(inst) {
    if (!inst) return '—';
    const d = new Date(inst);
    if (isNaN(d.getTime())) return '—';
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

/** schema 默认值：后端字段名为 defaultValue */
window.schemaDefault = function schemaDefault(field) {
    return field.defaultValue != null ? field.defaultValue : '';
};

/** 依据 schema 构造一份完整的表单模型，避免出现 undefined 绑定 */
window.buildModel = function buildModel(schema, source) {
    const model = {};
    (schema || []).forEach((f) => {
        const v = source ? source[f.name] : undefined;
        model[f.name] = (v === undefined || v === null) ? schemaDefault(f) : v;
    });
    return model;
};
