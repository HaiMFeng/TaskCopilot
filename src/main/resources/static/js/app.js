// TaskCopilot 前端逻辑（Vue 3 + Element Plus，全局构建，无打包依赖）。
// 数据层沿用 api.js；样式沿用 css/app.css。

const {createApp, ref, reactive, computed, onMounted, onBeforeUnmount, nextTick} = Vue;
const {ElMessage, ElMessageBox} = ElementPlus;

// 前端 JS 版本号（修改后请同步递增，便于辨识加载版本）
const APP_JS_VERSION = '20260805.07';

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
            {key: 'dashboard', label: '仪表盘', shortLabel: '仪表'},
            {key: 'schedule', label: '日程表', shortLabel: '日程'},
            {key: 'terminal', label: '终端', shortLabel: '终端'},
            {key: 'monitor', label: '屏幕', shortLabel: '屏幕'},
            {key: 'files', label: '文件管理器', shortLabel: '文件'},
        ];
        const mode = ref('dashboard');
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

        /* ---------------- 移动端适配 ---------------- */
        const isMobile = ref(window.innerWidth <= 768);
        const showMobileDetail = ref(false);
        window.addEventListener('resize', () => {
            isMobile.value = window.innerWidth <= 768;
        });
        function selectTaskMobile(id) {
            selectTask(id);
            showMobileDetail.value = true;
        }
        function closeMobileDetail() {
            showMobileDetail.value = false;
        }

        /* ---------------- 加载状态 ---------------- */
        const loading = ref(true);

        /* ---------------- 仪表盘 ---------------- */
        const dashInfo = ref({});
        const dashDashboard = ref({});
        const dashNetworks = ref([]);
        const dashNetConfig = ref({});
        const dashNetDown = ref('—');
        const dashNetUp = ref('—');

        const dashTime = ref('');
        const dashDate = ref('');
        function _tickDashTime() {
            const now = dayjs();
            dashTime.value = now.format('HH:mm:ss');
            dashDate.value = now.format('YYYY-MM-DD dddd');
        }

        // 可编辑用户名，默认 USER，存服务端
        const dashHostName = ref('USER');
        const editingHost = ref(false);
        const hostInput = ref(null);
        function startEditHost() {
            editingHost.value = true;
            nextTick(() => {
                const el = hostInput.value;
                if (el) { el.focus(); el.select(); }
            });
        }
        async function finishEditHost() {
            const val = dashHostName.value.trim();
            if (!val) dashHostName.value = 'USER';
            const nameToSave = dashHostName.value;
            editingHost.value = false;
            try {
                await API.updateDisplayName(nameToSave);
                dashHostName.value = nameToSave; // 确保不被轮询覆盖
            } catch (e) {
                ElMessage.error('保存失败');
            }
        }

        const dashMemUsed = computed(() => {
            const mb = dashInfo.value.usedPhysMemMb;
            if (mb == null) return '—';
            return mb >= 1024 ? (mb / 1024).toFixed(1) : (mb / 1024).toFixed(2);
        });
        const dashMemTotal = computed(() => {
            const mb = dashInfo.value.totalPhysMemMb;
            if (mb == null) return '—';
            return (mb / 1024).toFixed(1);
        });
        const dashMemPercent = computed(() => {
            const u = dashInfo.value.usedPhysMemMb;
            const t = dashInfo.value.totalPhysMemMb;
            if (!t || u == null) return 0;
            return Math.min(100, Math.round((u / t) * 1000) / 10);
        });

        const dashDiskFree = computed(() => {
            const gb = dashInfo.value.diskFreeGb;
            return gb != null ? gb.toFixed(1) : '—';
        });
        const dashDiskTotal = computed(() => {
            const gb = dashInfo.value.diskTotalGb;
            return gb != null ? gb.toFixed(1) : '—';
        });
        const dashDiskPercent = computed(() => {
            const f = dashInfo.value.diskFreeGb;
            const t = dashInfo.value.diskTotalGb;
            if (!t || f == null) return 0;
            return Math.min(100, Math.round(((t - f) / t) * 1000) / 10);
        });

        const dashUptime = computed(() => {
            const s = dashInfo.value.uptimeSeconds;
            if (s == null) return '—';
            const d = Math.floor(s / 86400);
            const h = Math.floor((s % 86400) / 3600);
            const m = Math.floor((s % 3600) / 60);
            if (d > 0) return d + ' 天 ' + h + ' 小时 ' + m + ' 分钟';
            if (h > 0) return h + ' 小时 ' + m + ' 分钟';
            return m + ' 分钟';
        });

        async function refreshDashboard() {
            try {
                const [info, db, net, netCfg] = await Promise.all([
                    API.systemInfo(),
                    API.dashboard(),
                    API.networkInfo(),
                    API.networkConfig(),
                ]);
                // 后端返回的是瞬时速率（Bytes/sec），直接格式化显示
                dashNetDown.value = formatBytesPerSec(info.netRxBytesPerSec || 0);
                dashNetUp.value = formatBytesPerSec(info.netTxBytesPerSec || 0);

                dashInfo.value = info;
                if (info.displayName && !editingHost.value) dashHostName.value = info.displayName;
                dashDashboard.value = db;
                dashNetworks.value = net || [];
                dashNetConfig.value = netCfg || {};
                schedulerInfo.value = info;
            } catch (e) {
                // 静默，保留上次数据
            }
        }

        function formatBytesPerSec(bytesPerSec) {
            if (bytesPerSec < 0) bytesPerSec = 0;
            if (bytesPerSec >= 1048576) return (bytesPerSec / 1048576).toFixed(1) + ' MB/s';
            if (bytesPerSec >= 1024) return (bytesPerSec / 1024).toFixed(1) + ' KB/s';
            return Math.round(bytesPerSec) + ' B/s';
        }

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
            // 离开屏幕页则停止截图轮询（按需启停，节省小主机资源）
            if (mode.value !== 'monitor') stopScreen();
            if (key === 'terminal') {
                openTerminalView();
            } else {
                stopPoll();
            }
            if (key === 'monitor') {
                startScreen();
            }
            if (key === 'schedule' && currentScheduleId.value) {
                loadTasks();
            }
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
            if (currentScheduleId.value === 0) return '不启用 · 任务列表';
            const s = schedules.value.find((x) => x.id === currentScheduleId.value);
            return s ? s.name + ' · 任务列表' : '任务列表';
        });

        const schedulerText = computed(() => {
            const info = schedulerInfo.value;
            if (!info) return '—';
            if (info.schedulerError) return `运行错误 · ${info.scheduledCount} 个任务`;
            if (info.schedulerPaused) return '已暂停';
            if (info.scheduledCount === 0) return '未运行';
            return `运行中 · ${info.scheduledCount} 个任务`;
        });
        const schedulerPillClass = computed(() => {
            const info = schedulerInfo.value;
            if (!info) return '';
            if (info.schedulerError) return 'error';
            if (info.schedulerPaused) return 'paused';
            if (info.scheduledCount === 0) return 'idle';
            return 'running';
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
        // 虚拟「不启用」日程表（id=0，不存数据库）
        const NO_SCHEDULE = reactive({id: 0, name: '不启用', taskCount: 0, active: false, virtual: true});
        const scheduleList = computed(() => {
            const list = schedules.value.slice();
            // 当没有任何日程表处于 active 时，「不启用」亮起
            NO_SCHEDULE.active = !schedules.value.some((s) => s.active);
            list.unshift(NO_SCHEDULE);
            return list;
        });

        async function loadSchedules() {
            schedules.value = await API.listSchedules();
        }

        async function selectSchedule(id) {
            if (currentScheduleId.value === id) return;
            currentScheduleId.value = id;
            selectedTaskId.value = null;
            selectedTask.value = null;
            if (id === 0) {
                tasks.value = [];
                return;
            }
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
                if (id === 0) {
                    await API.deactivateSchedule();
                    ElMessage.success('已停用所有日程表');
                } else {
                    await API.activateSchedule(id);
                    ElMessage.success('已切换运行日程表');
                }
                await loadSchedules();
                await selectSchedule(id);
                await loadSchedulerState(); // 刷新右上角状态
                refreshDashboard();         // 刷新仪表盘数据
            } catch (e) {
                ElMessage.error(e.message);
            }
        }

        async function deleteSchedule(id) {
            if (id === 0) return;
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

            // 仪表盘第一时间启动，不等待其他数据加载
            _tickDashTime();
            timers.push(setInterval(_tickDashTime, 1000));
            const dashReady = refreshDashboard(); // 不阻塞，但记住 Promise
            timers.push(setInterval(refreshDashboard, 3000));

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

            // 等待仪表盘首次数据就绪后再关闭遮罩
            try { await dashReady; } catch (e) { /* 即使失败也继续 */ }
            loading.value = false;
        });

        /* ---------------- 终端（只读输出 + 指令输入） ---------------- */
        const termRunning = ref(false);
        const termShell = ref('CMD');
        const termInput = ref('');
        const termOutputRef = ref(null);
        let termBuf = '';
        let termHasColor = false;

        function appendTermOutput(text) {
            termBuf += text;
            if (text.includes('\x1b')) termHasColor = true;
            const el = termOutputRef.value;
            if (!el) return;
            // 去掉 ANSI 转义序列后做纯文本展示，避免乱码控制字符
            const clean = stripAnsi(text);
            el.textContent += clean;
            // 自动滚动到底部
            el.scrollTop = el.scrollHeight;
        }

        function stripAnsi(str) {
            // 匹配 ANSI 转义序列（CSI 等）并移除
            return str.replace(/\x1b\[[0-9;?]*[ -/]*[@-~]/g, '');
        }

        function clearTermOutput() {
            termBuf = '';
            termHasColor = false;
            const el = termOutputRef.value;
            if (el) el.textContent = '';
        }

        /* ---------------- 屏幕查看（截图轮询） ---------------- */
        const screenUrl = ref('');                 // 当前帧 <img> 地址
        const screenQuality = ref(0.5);            // 清晰度：0.3/0.5/0.7/0.9
        const screenSize = ref('');                // 主屏尺寸（后端返回）
        const screenError = ref('');               // 不可用提示
        let screenTimer = null;

        async function refreshScreen() {
            try {
                const blob = await API.screenShot(screenQuality.value);
                screenUrl.value = URL.createObjectURL(blob) + '#' + Date.now();
                screenError.value = '';
            } catch (e) {
                // 503 等：后端无桌面环境
                screenError.value = '无法获取屏幕画面：' + (e.message || e);
            }
        }

        function startScreen() {
            if (screenTimer) return;
            refreshScreen();
            screenTimer = setInterval(refreshScreen, 1000);
        }

        function stopScreen() {
            if (screenTimer) { clearInterval(screenTimer); screenTimer = null; }
            if (screenUrl.value) { URL.revokeObjectURL(screenUrl.value.split('#')[0]); }
            screenUrl.value = '';
        }

        let termSeq = 0;
        let termTimer = null;
        let termPolling = false; // 是否处于常驻轮询（在终端页时为真）

        function stopPoll() {
            if (termTimer) { clearInterval(termTimer); termTimer = null; }
            termPolling = false;
        }

        // 根据后端权威状态，同步本地连接态与终端类型。返回当前是否处于「运行中」。
        async function syncTerminalState(data) {
            const running = data.running === true;
            // 同步终端类型（CMD/PowerShell）：以单例后端为准，所有端保持一致
            if (data.shell && termShell.value !== data.shell) {
                termShell.value = data.shell;
            }
            if (running && !termRunning.value) {
                // 其他端启动了本端未连接 → 自动接入
                termRunning.value = true;
                termSeq = data.latestSeq || 0;
                clearTermOutput();
                appendTermOutput('[已连接到 ' + (data.shell || 'CMD') + ' 终端，输入命令后回车发送]\r\n');
            } else if (!running && termRunning.value) {
                // 其他端停止了本端仍在运行 → 自动断开
                termRunning.value = false;
                termSeq = 0;
                clearTermOutput();
                appendTermOutput('[终端已停止]\r\n');
            } else if (!running && !termRunning.value) {
                // 保持未连接提示（仅首次进入渲染一次）
                if (termOutputRef.value && termOutputRef.value.textContent === '') {
                    appendTermOutput('[终端未启动，请点击「启动」开始]\r\n');
                }
            }
            return running;
        }

        async function pollTerminal() {
            try {
                const data = await API.terminalOutput(termSeq);
                const wasRunning = termRunning.value;
                const running = await syncTerminalState(data);
                if (running) {
                    const chunks = data.chunks || [];
                    for (const c of chunks) {
                        appendTermOutput(c.text);
                        if (c.seq > termSeq) termSeq = c.seq;
                    }
                }
            } catch (e) {
                // 轮询失败不阻断，下次继续
            }
        }

        async function startTerminal() {
            stopPoll();
            clearTermOutput();
            termSeq = 0;
            try {
                await API.terminalStart(termShell.value);
                termRunning.value = true;
                appendTermOutput('[已连接到 ' + termShell.value + ' 终端，输入命令后回车发送]\r\n');
                await pollTerminal();
                setTimeout(pollTerminal, 400);
                termTimer = setInterval(pollTerminal, 1000);
                termPolling = true;
            } catch (e) {
                termRunning.value = false;
                ElMessage.error('终端启动失败: ' + (e.message || e));
            }
        }

        async function openTerminalView() {
            // 进入终端页面即常驻轮询，状态以后端为准自动连接/断开
            stopPoll();
            clearTermOutput();
            termSeq = 0;
            termPolling = true;
            termTimer = setInterval(pollTerminal, 1000);
            await pollTerminal();
            setTimeout(pollTerminal, 400);
        }

        async function stopTerminal() {
            stopPoll();
            try {
                await API.terminalStop();
            } catch (e) {}
            // 立即本地切停，并停止轮询（本端不再自动重连）
            termRunning.value = false;
            termSeq = 0;
            clearTermOutput();
            appendTermOutput('[终端已停止]\r\n');
            // 其他端仍由各自轮询检测到 running=false 自动同步
        }

        async function sendTerminalCommand() {
            const cmd = termInput.value;
            if (!termRunning.value) {
                ElMessage.warning('终端未连接');
                return;
            }
            try {
                await API.terminalInput(cmd, termShell.value);
                termInput.value = '';
            } catch (e) {
                ElMessage.error('发送失败: ' + (e.message || e));
            }
        }

        async function sendTerminalInterrupt() {
            if (!termRunning.value) return;
            try {
                await API.terminalInterrupt();
            } catch (e) {}
        }


        function copyVersions() {
            const htmlV = window.__APP_HTML_VERSION__ || '?';
            const text = 'HTML ' + htmlV + '  JS ' + APP_JS_VERSION;
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text).then(() => {
                    ElMessage.success('已复制：' + text);
                }).catch(() => {
                    fallbackCopy(text);
                });
            } else {
                fallbackCopy(text);
            }
        }
        function fallbackCopy(text) {
            const ta = document.createElement('textarea');
            ta.value = text;
            ta.style.position = 'fixed';
            ta.style.left = '-9999px';
            ta.style.top = '-9999px';
            document.body.appendChild(ta);
            ta.focus();
            ta.select();
            try {
                document.execCommand('copy');
                ElMessage.success('已复制：' + text);
            } catch (e) {
                ElMessage.error('复制失败，请手动复制');
            }
            document.body.removeChild(ta);
        }

        onBeforeUnmount(() => {
            timers.forEach(clearInterval);
            timers = [];
            stopPoll();
            window.removeEventListener('resize', moveThumb);
        });

        return {
            loading,
            modes, mode, thumbStyle, setModeRef, switchMode,
            schedules, scheduleList, currentScheduleId, currentScheduleName,
            tasks, selectedTaskId, selectedTask, taskTypes,
            isMobile, showMobileDetail, selectTaskMobile, closeMobileDetail,
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
            // 仪表盘
            dashInfo, dashDashboard, dashNetworks, dashNetConfig, dashTime, dashDate,
            dashHostName, editingHost, hostInput, startEditHost, finishEditHost,
            dashMemUsed, dashMemTotal, dashMemPercent,
            dashDiskFree, dashDiskTotal, dashDiskPercent,
            dashNetDown, dashNetUp, dashUptime, copyVersions,
            termRunning, termShell, termInput, termOutputRef,
            startTerminal, stopTerminal, sendTerminalCommand, sendTerminalInterrupt,
            taskTime, taskMinute,
            fmtTime,
            // 屏幕查看
            screenUrl, screenQuality, screenSize, screenError,
        };
    },
})
    .use(ElementPlus)
    .mount('#app');
