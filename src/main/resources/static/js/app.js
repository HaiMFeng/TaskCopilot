// TaskCopilot 前端逻辑（Vue 3 + Element Plus，全局构建，无打包依赖）。
// 数据层沿用 api.js；样式沿用 css/app.css。

const {createApp, ref, reactive, computed, onMounted, onBeforeUnmount, nextTick} = Vue;
const {ElMessage, ElMessageBox} = ElementPlus;

// 前端 JS 版本号（修改后请同步递增，便于辨识加载版本）
const APP_JS_VERSION = '20260804.4';

/** 任务顶级字段（不放进 config，提交时提升到 payload 顶层） */
const TOP_LEVEL_FIELDS = new Set(['command', 'workingDir', 'timeoutSeconds']);

function fmtTime(inst) {
    if (!inst) return '—';
    const d = new Date(inst);
    if (isNaN(d.getTime())) return '—';
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** schema 默认值：后端字段名为 defaultValue */
function schemaDefault(field) {
    return field.defaultValue != null ? field.defaultValue : '';
}

/** 依据 schema 构造一份完整的表单模型，避免出现 undefined 绑定 */
function buildModel(schema, source) {
    const model = {};
    (schema || []).forEach((f) => {
        const v = source ? source[f.name] : undefined;
        model[f.name] = (v === undefined || v === null) ? schemaDefault(f) : v;
    });
    return model;
}

/**
 * 动态配置表单组件：依据后端下发的 configSchema 渲染控件。
 * 所有控件均使用 :model-value + @update:model-value，绝不把 undefined 传给 Element Plus。
 */
const ConfigFields = {
    name: 'ConfigFields',
    props: {
        schema: {type: Array, default: () => []},
        model: {type: Object, default: () => ({})},
        value: {type: Object, default: () => ({})},  // 兼容别名
        verifiedMap: {type: Object, default: () => ({})}, // 校验通过状态（与父级共享）
    },
    emits: ['update'],
    setup(props, {emit}) {
        // 统一读取：永远返回可安全绑定的值
        const val = (field) => {
            const v = props.model ? props.model[field.name] : undefined;
            if (v === undefined || v === null) {
                return field.type === 'number' ? undefined : '';
            }
            return v;
        };
        const set = (field, v) => emit('update', field.name, v);

        // ----- 应用路径输入框（appFile 类型） -----
        // 校验状态：每个 appFile 字段维护独立的校验结果
        const checking = ref(false);           // 校验中（按钮显示 loading）
        const checkResult = reactive({});      // { [fieldName]: {exists, isFile, extension, ok} }
        const verified = reactive({});         // { [fieldName]: boolean } 是否通过校验

        // ----- 进程选择器（process 类型） -----
        const processPickerVisible = ref(false);
        const processFilter = ref('');
        const allProcesses = ref([]);          // 从后端拉取的进程列表
        let pendingProcessField = null;        // 当前正在为其选择进程的字段
        const filteredProcesses = computed(() => {
            const kw = processFilter.value.toLowerCase().trim();
            if (!kw) return allProcesses.value;
            return allProcesses.value.filter((p) => p.name.toLowerCase().includes(kw));
        });
        /** 当用户选择了模糊匹配时，给出简短说明 */
        const wildcardTip = computed(() => {
            const matchField = (props.schema || []).find((f) => f.name === 'matchMode');
            if (!matchField) return '';
            return val(matchField) === 'wildcard'
                ? '模糊匹配会杀死所有进程名包含该关键词的进程（如输入 chrome 会匹配 chrome.exe、chrome_helper.exe 等），请谨慎使用。'
                : '';
        });

        async function openProcessPicker(field) {
            pendingProcessField = field;
            processFilter.value = '';
            // 每次打开都重新拉取，确保进程列表反映当前实际运行状态
            try {
                allProcesses.value = await API.fetchProcesses();
            } catch (e) {
                ElMessage.error('获取进程列表失败：' + e.message);
                allProcesses.value = [];
            }
            processPickerVisible.value = true;
        }
        function pickProcess(name) {
            if (pendingProcessField) {
                set(pendingProcessField, name);
            }
            processPickerVisible.value = false;
            pendingProcessField = null;
        }

        /** 清洗路径：去除首尾空白与包裹的引号 */
        function cleanPath(raw) {
            if (!raw) return '';
            let p = String(raw).trim();
            // 去除成对引号（" 或 '）
            p = p.replace(/^["']+/, '').replace(/["']+$/, '');
            // 规范化反斜杠
            return p.trim();
        }

        /** 从路径提取文件名（用于校验成功标签展示） */
        function fileBaseName(field) {
            const p = cleanPath(val(field));
            if (!p) return '';
            const idx = Math.max(p.lastIndexOf('\\'), p.lastIndexOf('/'));
            return idx >= 0 ? p.substring(idx + 1) : p;
        }

        /** 手动校验指定字段的当前路径 */
        async function verifyField(field) {
            const path = cleanPath(val(field));
            if (!path) {
                checkResult[field.name] = null;
                verified[field.name] = false;
                props.verifiedMap[field.name] = false;
                return;
            }
            checking.value = true;
            const start = Date.now();
            try {
                const res = await API.checkPath(path);
                checkResult[field.name] = res;
                const ok = !!(res && res.ok);
                verified[field.name] = ok;
                props.verifiedMap[field.name] = ok;
            } catch (e) {
                checkResult[field.name] = null;
                verified[field.name] = false;
                props.verifiedMap[field.name] = false;
            } finally {
                // 保证加载圈至少显示 400ms，避免网络过快造成的闪烁/抽搐
                const elapsed = Date.now() - start;
                if (elapsed < 400) {
                    await new Promise((r) => setTimeout(r, 400 - elapsed));
                }
                checking.value = false;
            }
        }

        /** 输入框内容变动：更新模型、重置该校验状态 */
        function onPathInput(field, v) {
            set(field, v);
            checkResult[field.name] = null;
            verified[field.name] = false;
            props.verifiedMap[field.name] = false;
        }

        /** 对所有已有值的 appFile 字段自动校验一次（选中任务/切换类型时调用） */
        async function autoVerifyAll() {
            const fields = (props.schema || []).filter((f) => f.type === 'appFile');
            for (const f of fields) {
                const path = cleanPath(val(f));
                checkResult[f.name] = null;
                verified[f.name] = false;
                props.verifiedMap[f.name] = false;
                if (path) {
                    await verifyField(f);
                }
            }
        }

        // 模型被整体替换（选中任务、切换类型）时自动校验一次；
        // 注意 deep:false —— 手动输入只改属性不触发，避免与 onPathInput 冲突
        const { watch, onMounted, nextTick } = Vue;
        watch(() => props.model, () => {
            nextTick(() => autoVerifyAll());
        }, {deep: false});

        // schema 变化（任务类型切换后字段变了）同样自动校验
        watch(() => props.schema, () => {
            nextTick(() => autoVerifyAll());
        }, {deep: false});

        onMounted(() => {
            nextTick(() => autoVerifyAll());
        });

        return {val, set, checking, checkResult, verified,
                verifyField, onPathInput, cleanPath, fileBaseName, autoVerifyAll,
                processPickerVisible, processFilter, filteredProcesses, wildcardTip,
                openProcessPicker, pickProcess};
    },
    template: `
        <div class="config-fields">
            <el-form-item v-for="f in schema" :key="f.name" :required="f.required">
                <template #label>
                    <span>{{ f.label }}<span v-if="f.required" class="req-star"> *</span></span>
                </template>

                <el-select v-if="f.type === 'select'"
                           :model-value="val(f)"
                           style="width:100%"
                           @update:model-value="v => set(f, v)">
                    <el-option v-for="o in (f.options || [])"
                               :key="String(o.value)"
                               :label="o.label"
                               :value="o.value"/>
                </el-select>

                <el-input v-else-if="f.type === 'textarea'"
                          type="textarea"
                          :rows="2"
                          :model-value="val(f)"
                          :placeholder="f.help || ''"
                          @update:model-value="v => set(f, v)"/>

                <el-input-number v-else-if="f.type === 'number'"
                                 :model-value="val(f)"
                                 :min="f.min != null ? f.min : 0"
                                 :max="f.max != null ? f.max : Infinity"
                                 controls-position="right"
                                 style="width:100%"
                                 @update:model-value="v => set(f, v)"/>

                <el-time-picker v-else-if="f.type === 'time'"
                                :model-value="val(f)"
                                format="HH:mm"
                                value-format="HH:mm"
                                placeholder="选择时间"
                                style="width:100%"
                                @update:model-value="v => set(f, v || '')"/>

                <el-switch v-else-if="f.type === 'boolean'"
                           :model-value="!!val(f)"
                           @update:model-value="v => set(f, v)"/>

                <el-input v-else-if="f.type !== 'appFile' && f.type !== 'process'"
                          :model-value="val(f)"
                          :placeholder="f.help || ''"
                          @update:model-value="v => set(f, v)"/>

                <template v-else-if="f.type === 'appFile'">
                    <div class="path-row">
                        <el-input :model-value="val(f)"
                                  :placeholder="f.help || '请输入应用路径，如 C:/Program Files/xxx/app.exe'"
                                  @update:model-value="v => onPathInput(f, v)"/>
                        <transition name="slide">
                            <el-button v-if="val(f) && verified[f.name] !== true"
                                       class="path-verify-btn"
                                       :loading="checking"
                                       @click="verifyField(f)">校验</el-button>
                        </transition>
                    </div>
                    <transition name="fade">
                        <div class="dz-check" v-if="val(f) && checkResult[f.name]">
                            <el-tag v-if="checkResult[f.name].ok" type="success" size="small">
                                ✓ 已找到 {{ fileBaseName(f) }}
                            </el-tag>
                            <el-tag v-else type="danger" size="small">
                                ✗ {{ checkResult[f.name].exists ? (checkResult[f.name].isFile ? '不是文件' : '不是文件夹') : '路径不存在' }}
                            </el-tag>
                        </div>
                    </transition>
                </template>

                <template v-else-if="f.type === 'process'">
                    <div class="path-row">
                        <el-input :model-value="val(f)"
                                  :placeholder="f.help || '输入进程名，如 notepad.exe'"
                                  @update:model-value="v => set(f, v)"/>
                        <el-button class="path-verify-btn"
                                   @click="openProcessPicker(f)">选择进程</el-button>
                    </div>
                    <transition name="fade">
                        <div class="dz-check" v-if="val(f)">
                            <el-tag type="info" size="small">进程名：{{ val(f) }}</el-tag>
                        </div>
                    </transition>
                    <div v-if="wildcardTip" class="field-help" style="margin-top:4px">{{ wildcardTip }}</div>
                </template>

                <!-- 进程选择弹窗 -->
                <el-dialog v-model="processPickerVisible" title="选择运行中的进程" width="460px" destroy-on-close>
                    <el-input v-model="processFilter" placeholder="搜索进程名..." clearable style="margin-bottom:12px"/>
                    <div class="process-list">
                        <div v-for="p in filteredProcesses" :key="p.name"
                             class="process-item"
                             @click="pickProcess(p.name)">
                            {{ p.name }}
                        </div>
                        <div v-if="filteredProcesses.length === 0" class="process-empty">无匹配进程</div>
                    </div>
                </el-dialog>

                <div v-if="f.help && f.type !== 'text' && f.type !== 'textarea' && f.type !== 'appFile' && f.type !== 'process'" class="field-help">{{ f.help }}</div>
            </el-form-item>
        </div>
    `,
};

createApp({
    components: {ConfigFields},
    setup() {
        /* ---------------- 基础状态 ---------------- */
        const modes = [
            {key: 'daily', label: '每日任务'},
            {key: 'immediate', label: '立即任务'},
        ];
        const mode = ref('daily');
        const modeRefs = {};
        const thumbStyle = reactive({width: '0px', transform: 'translateX(0px)'});

        const schedules = ref([]);
        const currentScheduleId = ref(null);
        const tasks = ref([]);
        const selectedTaskId = ref(null);
        const taskTypes = ref([]);

        const saving = ref(false);
        const running = ref(false);
        const creating = ref(false);

        const createVisible = ref(false);
        const historyVisible = ref(false);
        const historyText = ref('');
        const resultOutput = ref(null);

        const schedulerInfo = ref(null);

        /* ---------------- 模式切换滑块 ---------------- */
        function setModeRef(key, el) {
            if (el) modeRefs[key] = el;
        }
        function moveThumb() {
            const el = modeRefs[mode.value];
            if (!el) return;
            thumbStyle.width = el.offsetWidth + 'px';
            thumbStyle.transform = `translateX(${el.offsetLeft}px)`;
        }
        function switchMode(key) {
            if (mode.value === key) return;
            mode.value = key;
            nextTick(moveThumb);
        }

        /* ---------------- 表单模型 ---------------- */
        const form = reactive({id: null, name: '', typeCode: '', remark: '', config: {}});
        const createForm = reactive({name: '', typeCode: '', remark: '', config: {}});

        // appFile 字段的校验通过状态（与 ConfigFields 子组件共享）
        const appFileVerified = reactive({});       // 详情表单
        const createFileVerified = reactive({});    // 新建表单

        const schemaOf = (typeCode) => {
            const t = taskTypes.value.find((x) => x.typeCode === typeCode);
            return (t && t.configSchema) ? t.configSchema : [];
        };
        const detailSchema = computed(() => schemaOf(form.typeCode));
        const createSchema = computed(() => schemaOf(createForm.typeCode));

        const onDetailFieldUpdate = (name, v) => { form.config[name] = v; };
        const onCreateFieldUpdate = (name, v) => { createForm.config[name] = v; };

        // 切换类型时保留同名字段已填值
        function onTypeChange() {
            form.config = buildModel(detailSchema.value, form.config);
        }
        function onCreateTypeChange() {
            createForm.config = buildModel(createSchema.value, createForm.config);
            Object.keys(createFileVerified).forEach((k) => { delete createFileVerified[k]; });
        }

        /* ---------------- 派生显示 ---------------- */
        const selectedTask = ref(null);

        const currentScheduleName = computed(() => {
            const s = schedules.value.find((x) => x.id === currentScheduleId.value);
            return s ? s.name + ' · 任务列表' : '任务列表';
        });

        const schedulerText = computed(() => {
            const info = schedulerInfo.value;
            if (!info) return '—';
            if (info.schedulerError) return `运行错误 · ${info.scheduledCount} 个任务`;
            if (info.schedulerPaused) return '已暂停';
            return `运行中 · ${info.scheduledCount} 个任务`;
        });
        const schedulerPillClass = computed(() => {
            const info = schedulerInfo.value;
            if (!info) return '';
            if (info.schedulerError) return 'error';
            return info.schedulerPaused ? 'paused' : 'running';
        });

        const statusClass = (t) => (!t.lastStatus ? 'idle' : (t.lastStatus === 'SUCCESS' ? 'ok' : 'fail'));

        const lastStatusSuffix = computed(() => {
            const t = selectedTask.value;
            if (!t || !t.lastStatus) return '';
            return `（${t.lastStatus === 'SUCCESS' ? '成功' : '失败'}）`;
        });

        const showResult = computed(() => !!selectedTask.value);
        const hasRun = computed(() => {
            const t = selectedTask.value;
            return !!(t && t.lastExecutedAt && t.lastStatus);
        });
        const resultHasError = computed(() => {
            const t = selectedTask.value;
            return !!(t && t.lastStatus !== 'SUCCESS');
        });
        const resultBadgeClass = computed(() => (resultHasError.value ? 'fail' : 'ok'));
        const resultBadgeText = computed(() => {
            const t = selectedTask.value;
            if (!t || !t.lastStatus) return '尚未运行';
            const s = t.lastStatus === 'SUCCESS' ? '成功' : (t.lastStatus === 'TIMEOUT' ? '超时' : '失败');
            return s + (t.lastExitCode != null ? ` · 退出码 ${t.lastExitCode}` : '');
        });
        const resultText = computed(() => {
            const t = selectedTask.value;
            if (!t) return '';
            const out = [];
            if (t.lastStdout) out.push('--- stdout ---\n' + t.lastStdout);
            if (t.lastStderr) out.push('--- stderr ---\n' + t.lastStderr);
            return out.join('\n\n') || '（无输出）';
        });

        /* ---------------- 日程表 ---------------- */
        async function loadSchedules() {
            schedules.value = await API.listSchedules();
        }

        async function selectSchedule(id) {
            if (currentScheduleId.value === id) return;
            currentScheduleId.value = id;
            selectedTaskId.value = null;
            selectedTask.value = null;
            await loadTasks();
        }

        async function createSchedule() {
            try {
                const {value} = await ElMessageBox.prompt('请输入日程表名称：', '新建日程表', {
                    confirmButtonText: '创建',
                    cancelButtonText: '取消',
                    inputValidator: (v) => (v && v.trim() ? true : '名称不能为空'),
                });
                const created = await API.createSchedule({name: value.trim()});
                ElMessage.success('已创建日程表');
                await loadSchedules();
                await selectSchedule(created.id);
            } catch (e) {
                if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || String(e));
            }
        }

        async function activateSchedule(id) {
            try {
                await API.activateSchedule(id);
                ElMessage.success('已切换运行日程表');
                await loadSchedules();
                await selectSchedule(id);
            } catch (e) {
                ElMessage.error(e.message);
            }
        }

        async function deleteSchedule(id) {
            const sch = schedules.value.find((s) => s.id === id);
            try {
                const count = sch ? sch.taskCount : 0;
                const tip = count > 0
                    ? `其下 ${count} 个任务及其执行记录将一并删除，且无法恢复。`
                    : '该日程表下暂无任务。';
                await ElMessageBox.confirm(
                    `确定删除日程表「${sch ? sch.name : id}」？${tip}`,
                    '删除确认',
                    {type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'}
                );
            } catch (_) {
                return;
            }
            try {
                await API.deleteSchedule(id);
                ElMessage.success('已删除日程表');
                await loadSchedules();
                if (schedules.value.length > 0) {
                    currentScheduleId.value = null;
                    await selectSchedule(schedules.value[0].id);
                } else {
                    currentScheduleId.value = null;
                    tasks.value = [];
                    selectedTaskId.value = null;
                    selectedTask.value = null;
                }
            } catch (e) {
                ElMessage.error(e.message);
            }
        }

        /* ---------------- 任务 ---------------- */
        async function loadTasks() {
            if (!currentScheduleId.value) {
                tasks.value = [];
                return;
            }
            tasks.value = await API.listTasksBySchedule(currentScheduleId.value);
        }

        function fillForm(task) {
            form.id = task.id;
            form.name = task.name;
            form.typeCode = task.typeCode;
            form.remark = task.remark || '';
            // command/workingDir/timeoutSeconds 是顶层字段，但由 schema 渲染，需要合并回填
            const merged = Object.assign({}, task.config || {}, {
                command: task.command,
                workingDir: task.workingDir,
                timeoutSeconds: task.timeoutSeconds,
            });
            form.config = buildModel(schemaOf(task.typeCode), merged);
            // 载入新任务时重置应用路径校验状态，需用户重新校验方可保存
            Object.keys(appFileVerified).forEach((k) => { delete appFileVerified[k]; });
        }

        function selectTask(id) {
            selectedTaskId.value = id;
            const task = tasks.value.find((t) => t.id === id) || null;
            selectedTask.value = task;
            if (task) fillForm(task);
        }

        async function toggleTask(task, val) {
            try {
                await API.toggleTask(task.id, val);
                task.enabled = val;
                ElMessage.success('已更新启用状态');
            } catch (e) {
                ElMessage.error(e.message);
                await loadTasks();
            }
        }

        /** 把表单拆成 config + 顶层字段 */
        function splitPayload(schema, config) {
            const cfg = {};
            const top = {};
            (schema || []).forEach((f) => {
                const v = config[f.name];
                if (TOP_LEVEL_FIELDS.has(f.name)) top[f.name] = v;
                else cfg[f.name] = v;
            });
            return {config: cfg, top};
        }

        function buildPayload(src, schema) {
            const {config, top} = splitPayload(schema, src.config);
            const wd = top.workingDir;
            // 清洗 appFile 字段中的路径（去除引号/空白），保证提交到后端的是干净路径
            const cleanedConfig = {...config};
            (schema || []).forEach((f) => {
                if (f.type === 'appFile' && cleanedConfig[f.name] != null) {
                    let p = String(cleanedConfig[f.name]).trim();
                    p = p.replace(/^["']+/, '').replace(/["']+$/, '').trim();
                    cleanedConfig[f.name] = p;
                }
            });
            return {
                name: (src.name || '').trim(),
                command: top.command != null ? top.command : '',
                workingDir: (typeof wd === 'string' && wd.trim() !== '') ? wd.trim() : null,
                typeCode: src.typeCode,
                config: cleanedConfig,
                timeoutSeconds: (top.timeoutSeconds != null && top.timeoutSeconds !== '')
                    ? Number(top.timeoutSeconds) : null,
                remark: (src.remark || '').trim() || null,
                scheduleId: currentScheduleId.value,
            };
        }

        /** 检查某 schema 下所有 appFile 字段是否都已校验通过 */
        function allAppFilesVerified(schema, verifiedMap) {
            const fields = (schema || []).filter((f) => f.type === 'appFile');
            if (fields.length === 0) return true;
            return fields.every((f) => verifiedMap[f.name] === true);
        }

        async function _doSave() {
            if (!form.name.trim()) {
                ElMessage.error('任务名称不能为空');
                return false;
            }
            if (!allAppFilesVerified(detailSchema.value, appFileVerified)) {
                ElMessage.error('请先校验应用路径，路径合法后方可保存');
                return false;
            }
            saving.value = true;
            try {
                const updated = await API.updateTask(form.id, buildPayload(form, detailSchema.value));
                await loadTasks();
                selectTask(updated.id);
                return true;
            } catch (e) {
                ElMessage.error(e.message);
                return false;
            } finally {
                saving.value = false;
            }
        }

        async function saveDetail() {
            const ok = await _doSave();
            if (ok) ElMessage.success('已保存');
        }

        async function runTask() {
            // 先保存，确保执行的是最新配置
            const ok = await _doSave();
            if (!ok) return;
            const id = form.id;
            const wasEnabled = selectedTask.value ? selectedTask.value.enabled : false;
            running.value = true;
            try {
                const logResult = await API.executeTask(id);
                // 直接拉取该任务最新状态：不依赖整列重载，
                // 避免立即任务模式下 loadTasks 因 currentScheduleId 为空而清空列表、导致结果消失
                const fresh = await API.getTask(id);
                const idx = tasks.value.findIndex((t) => t.id === id);
                if (idx >= 0) {
                    tasks.value[idx] = fresh;
                    tasks.value = tasks.value.slice();
                } else {
                    tasks.value = [...tasks.value, fresh];
                }
                selectedTask.value = fresh;
                selectedTaskId.value = id;
                fillForm(fresh);
                await nextTick();
                if (resultOutput.value) {
                    resultOutput.value.scrollIntoView({behavior: 'smooth', block: 'nearest'});
                }
                // 执行失败的任务会被后端自动停用，此处据实提示，避免仍显示"执行完成"
                const failed = logResult && logResult.status && logResult.status !== 'SUCCESS';
                if (failed) {
                    if (wasEnabled && fresh && fresh.enabled === false) {
                        ElMessage.warning('执行失败，已自动关闭任务开关');
                    } else {
                        ElMessage.error('执行失败，请查看下方输出');
                    }
                } else {
                    ElMessage.success('执行完成');
                }
            } catch (e) {
                ElMessage.error(e.message);
            } finally {
                running.value = false;
            }
        }

        async function viewHistory() {
            try {
                const logs = await API.taskLogs(form.id, 20);
                if (!logs || logs.length === 0) {
                    ElMessage.warning('暂无历史记录');
                    return;
                }
                historyText.value = logs.map((l, i) => {
                    const head = `#${logs.length - i}  ${fmtTime(l.startedAt)}  [${l.status}]  退出码 ${l.exitCode}`;
                    const out = [
                        l.stdout ? `--- stdout ---\n${l.stdout}` : '',
                        l.stderr ? `--- stderr ---\n${l.stderr}` : '',
                    ].filter(Boolean).join('\n');
                    return head + (out ? '\n' + out : '');
                }).join('\n\n' + '─'.repeat(40) + '\n\n');
                historyVisible.value = true;
            } catch (e) {
                ElMessage.error(e.message);
            }
        }

        async function deleteTask() {
            try {
                await ElMessageBox.confirm('确定删除该任务？', '删除确认', {
                    type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
                });
            } catch (_) {
                return;
            }
            try {
                await API.deleteTask(form.id);
                ElMessage.success('已删除任务');
                selectedTaskId.value = null;
                selectedTask.value = null;
                await loadTasks();
            } catch (e) {
                ElMessage.error(e.message);
            }
        }

        /* ---------------- 新建任务 ---------------- */
        function openCreateTask() {
            if (!currentScheduleId.value) {
                ElMessage.warning('请先选择一个日程表');
                return;
            }
            createForm.name = '';
            createForm.remark = '';
            createForm.typeCode = taskTypes.value.length ? taskTypes.value[0].typeCode : '';
            createForm.config = buildModel(schemaOf(createForm.typeCode), {});
            Object.keys(createFileVerified).forEach((k) => { delete createFileVerified[k]; });
            createVisible.value = true;
        }

        async function submitCreate() {
            if (!createForm.name.trim()) {
                ElMessage.error('任务名称不能为空');
                return;
            }
            if (!allAppFilesVerified(createSchema.value, createFileVerified)) {
                ElMessage.error('请先校验应用路径，路径合法后方可创建');
                return;
            }
            creating.value = true;
            try {
                const payload = buildPayload(createForm, createSchema.value);
                payload.enabled = true;
                await API.createTask(payload);
                createVisible.value = false;
                ElMessage.success('已创建任务');
                await loadTasks();
            } catch (e) {
                ElMessage.error(e.message);
            } finally {
                creating.value = false;
            }
        }

        /* ---------------- 触发时间 ---------------- */
        /** 取任务的触发时间文本（HH:mm），配置缺失时回退到默认 08:30 */
        function taskTime(t) {
            const raw = t && t.config ? t.config.time : null;
            if (typeof raw === 'string' && raw.trim()) {
                const parts = raw.trim().split(':');
                const h = parseInt(parts[0], 10);
                const m = parts.length > 1 ? parseInt(parts[1], 10) : 0;
                if (!isNaN(h) && !isNaN(m)) {
                    return String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0');
                }
            }
            return '08:30';
        }
        /** 触发时间对应的当日分钟数，用于比较与排序 */
        function taskMinute(t) {
            const parts = taskTime(t).split(':');
            return parseInt(parts[0], 10) * 60 + parseInt(parts[1], 10);
        }

        /* ---------------- 拖拽排序 ---------------- */
        // 列表以触发时间为主序，拖拽仅用于调整「同一时间」任务的先后
        const dragIndex = ref(-1);
        /** 仅当存在同一执行时间的其它任务时，该项才可拖拽 */
        function canDrag(idx) {
            const list = tasks.value;
            if (!list || list.length < 2) return false;
            const m = taskMinute(list[idx]);
            return (idx > 0 && taskMinute(list[idx - 1]) === m)
                || (idx < list.length - 1 && taskMinute(list[idx + 1]) === m);
        }
        function onDragStart(idx) { dragIndex.value = idx; }
        function onDragOver(idx) {
            if (dragIndex.value === -1 || dragIndex.value === idx) return;
            // 跨执行时间不允许调整顺序，直接忽略此次移动
            if (taskMinute(tasks.value[dragIndex.value]) !== taskMinute(tasks.value[idx])) return;
            const arr = tasks.value.slice();
            const [moved] = arr.splice(dragIndex.value, 1);
            arr.splice(idx, 0, moved);
            tasks.value = arr;
            dragIndex.value = idx;
        }
        async function onDragEnd() {
            if (dragIndex.value === -1) return;
            dragIndex.value = -1;
            try {
                await API.reorderTasks(tasks.value.map((t) => t.id));
            } catch (e) {
                ElMessage.error('排序保存失败：' + e.message);
                await loadTasks();
            }
        }

        /* ---------------- 轮询 ---------------- */
        async function loadSchedulerState() {
            try {
                schedulerInfo.value = await API.systemInfo();
            } catch (_) {
                schedulerInfo.value = null;
            }
        }

        async function pollTasks() {
            if (!currentScheduleId.value) return;
            try {
                // 仅更新派生状态，不覆盖用户正在编辑的表单
                tasks.value = await API.listTasksBySchedule(currentScheduleId.value);
                if (selectedTaskId.value != null) {
                    const t = tasks.value.find((x) => x.id === selectedTaskId.value);
                    if (t) selectedTask.value = t;
                }
            } catch (_) { /* 静默 */ }
        }

        let timers = [];
        onMounted(async () => {
            nextTick(moveThumb);
            window.addEventListener('resize', moveThumb);

            try {
                taskTypes.value = await API.taskTypes();
            } catch (e) {
                ElMessage.error('加载任务类型失败：' + e.message);
            }

            try {
                const cur = await API.currentSchedule();
                await loadSchedules();
                if (schedules.value.length > 0) {
                    await selectSchedule((cur && cur.id) != null ? cur.id : schedules.value[0].id);
                }
            } catch (e) {
                ElMessage.error('加载日程表失败：' + e.message);
            }

            await loadSchedulerState();
            timers.push(setInterval(loadSchedulerState, 15000));
            timers.push(setInterval(pollTasks, 8000));

            // 填充右下角版本标识
            const htmlVer = document.getElementById('htmlVer');
            const jsVer = document.getElementById('jsVer');
            if (htmlVer) htmlVer.textContent = window.__APP_HTML_VERSION__ || '?';
            if (jsVer) jsVer.textContent = APP_JS_VERSION;
        });

        onBeforeUnmount(() => {
            timers.forEach(clearInterval);
            timers = [];
            window.removeEventListener('resize', moveThumb);
        });

        return {
            modes, mode, thumbStyle, setModeRef, switchMode,
            schedules, currentScheduleId, currentScheduleName,
            tasks, selectedTaskId, selectedTask, taskTypes,
            form, createForm, detailSchema, createSchema,
            appFileVerified, createFileVerified,
            onDetailFieldUpdate, onCreateFieldUpdate, onTypeChange, onCreateTypeChange,
            saving, running, creating,
            createVisible, historyVisible, historyText, resultOutput,
            schedulerText, schedulerPillClass, statusClass, lastStatusSuffix,
            showResult, hasRun, resultHasError, resultBadgeClass, resultBadgeText, resultText,
            selectSchedule, createSchedule, activateSchedule, deleteSchedule,
            selectTask, toggleTask, saveDetail, runTask, viewHistory, deleteTask,
            openCreateTask, submitCreate,
            dragIndex, onDragStart, onDragOver, onDragEnd, canDrag,
            taskTime, taskMinute,
            fmtTime,
        };
    },
})
    .use(ElementPlus)
    .mount('#app');
