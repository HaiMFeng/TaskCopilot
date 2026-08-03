// TaskCopilot 前端逻辑（Vue 3 + Element Plus，全局构建，无打包依赖）。
// 数据层沿用 api.js；样式沿用 css/app.css。

const {createApp, ref, reactive, computed, onMounted, onBeforeUnmount, nextTick} = Vue;
const {ElMessage, ElMessageBox} = ElementPlus;

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
        return {val, set};
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

                <el-input v-else
                          :model-value="val(f)"
                          :placeholder="f.help || ''"
                          @update:model-value="v => set(f, v)"/>

                <div v-if="f.help && f.type !== 'text' && f.type !== 'textarea'" class="field-help">{{ f.help }}</div>
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
        }

        /* ---------------- 派生显示 ---------------- */
        const selectedTask = computed(() => tasks.value.find((t) => t.id === selectedTaskId.value) || null);

        const currentScheduleName = computed(() => {
            const s = schedules.value.find((x) => x.id === currentScheduleId.value);
            return s ? s.name + ' · 任务列表' : '任务列表';
        });

        const schedulerText = computed(() => {
            const info = schedulerInfo.value;
            if (!info) return '—';
            return info.schedulerPaused ? '已暂停' : `运行中 · ${info.scheduledCount} 个任务`;
        });
        const schedulerPillClass = computed(() => {
            const info = schedulerInfo.value;
            if (!info) return '';
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
                await ElMessageBox.confirm(
                    `确定删除日程表「${sch ? sch.name : id}」？其下任务将变为未分组。`,
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
        }

        function selectTask(id) {
            selectedTaskId.value = id;
            const task = tasks.value.find((t) => t.id === id);
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
            return {
                name: (src.name || '').trim(),
                command: top.command != null ? top.command : '',
                workingDir: (typeof wd === 'string' && wd.trim() !== '') ? wd.trim() : null,
                typeCode: src.typeCode,
                config,
                timeoutSeconds: (top.timeoutSeconds != null && top.timeoutSeconds !== '')
                    ? Number(top.timeoutSeconds) : null,
                remark: (src.remark || '').trim() || null,
                scheduleId: currentScheduleId.value,
            };
        }

        async function saveDetail() {
            if (!form.name.trim()) {
                ElMessage.error('任务名称不能为空');
                return;
            }
            saving.value = true;
            try {
                const updated = await API.updateTask(form.id, buildPayload(form, detailSchema.value));
                ElMessage.success('已保存');
                await loadTasks();
                selectTask(updated.id);
            } catch (e) {
                ElMessage.error(e.message);
            } finally {
                saving.value = false;
            }
        }

        async function runTask() {
            const id = form.id;
            running.value = true;
            try {
                await API.executeTask(id);
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
                selectTask(id);
                await nextTick();
                if (resultOutput.value) {
                    resultOutput.value.scrollIntoView({behavior: 'smooth', block: 'nearest'});
                }
                ElMessage.success('执行完成');
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
            createVisible.value = true;
        }

        async function submitCreate() {
            if (!createForm.name.trim()) {
                ElMessage.error('任务名称不能为空');
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

        /* ---------------- 拖拽排序 ---------------- */
        const dragIndex = ref(-1);
        function onDragStart(idx) { dragIndex.value = idx; }
        function onDragOver(idx) {
            if (dragIndex.value === -1 || dragIndex.value === idx) return;
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
            onDetailFieldUpdate, onCreateFieldUpdate, onTypeChange, onCreateTypeChange,
            saving, running, creating,
            createVisible, historyVisible, historyText, resultOutput,
            schedulerText, schedulerPillClass, statusClass, lastStatusSuffix,
            showResult, resultHasError, resultBadgeClass, resultBadgeText, resultText,
            selectSchedule, createSchedule, activateSchedule, deleteSchedule,
            selectTask, toggleTask, saveDetail, runTask, viewHistory, deleteTask,
            openCreateTask, submitCreate,
            dragIndex, onDragStart, onDragOver, onDragEnd,
            fmtTime,
        };
    },
})
    .use(ElementPlus)
    .mount('#app');
