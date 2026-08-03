// TaskCopilot 前端逻辑（原生 JS，无构建依赖）。
// 视图层保持轻量；与后端交互全部经由 API 封装层。

const state = {
    mode: 'daily',            // 'daily' | 'immediate'
    schedules: [],            // 日程表列表
    currentScheduleId: null,  // 当前选中的日程表
    tasks: [],                // 当前日程表下的任务
    selectedTaskId: null,     // 当前查看的任务
    taskTypes: [],            // 任务类型 schema
};

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

function toast(msg, isError = false) {
    const t = $('#toast');
    t.textContent = msg;
    t.className = 'toast' + (isError ? ' error' : '');
    t.classList.remove('hidden');
    clearTimeout(toast._t);
    toast._t = setTimeout(() => t.classList.add('hidden'), 2600);
}

function fmtTime(inst) {
    if (!inst) return '—';
    const d = new Date(inst);
    if (isNaN(d)) return '—';
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function escapeHtml(s) {
    return String(s ?? '').replace(/[&<>"']/g, (c) => (
        {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]
    ));
}

/* ---------------- 模式切换滑块 ---------------- */
function setupModeSwitch() {
    const thumb = $('#modeThumb');
    function moveThumb() {
        const active = $('.mode-option.active');
        thumb.style.width = active.offsetWidth + 'px';
        thumb.style.transform = `translateX(${active.offsetLeft}px)`;
    }
    $$('.mode-option').forEach((opt) => {
        opt.addEventListener('click', () => {
            const mode = opt.dataset.mode;
            if (mode === state.mode) return;
            state.mode = mode;
            $$('.mode-option').forEach((o) => o.classList.toggle('active', o.dataset.mode === mode));
            moveThumb();
            $('#dailyView').classList.toggle('hidden', mode !== 'daily');
            $('#immediateView').classList.toggle('hidden', mode !== 'immediate');
        });
    });
    window.addEventListener('resize', moveThumb);
    requestAnimationFrame(moveThumb);
}

/* ---------------- 日程表 ---------------- */
async function loadSchedules() {
    state.schedules = await API.listSchedules();
    renderSchedules();
}

function renderSchedules() {
    const wrap = $('#scheduleList');
    wrap.innerHTML = '';
    if (state.schedules.length === 0) {
        wrap.innerHTML = '<div class="empty-hint">暂无日程表，点击 ＋ 创建</div>';
        return;
    }
    state.schedules.forEach((s) => {
        const item = document.createElement('div');
        item.className = 'schedule-item' + (s.active ? ' running' : '') + (s.id === state.currentScheduleId ? ' selected' : '');
        item.dataset.id = s.id;
        item.innerHTML = `
            <div class="schedule-meta">
                <span class="schedule-dot"></span>
                <span class="schedule-name">${escapeHtml(s.name)}</span>
            </div>
            <span class="schedule-count">${s.taskCount} 任务</span>
        `;
        item.addEventListener('click', () => selectSchedule(s.id));
        wrap.appendChild(item);
    });
}

async function selectSchedule(id) {
    state.currentScheduleId = id;
    state.selectedTaskId = null;
    renderSchedules();
    const sch = state.schedules.find((s) => s.id === id);
    $('#currentScheduleName').textContent = sch ? sch.name + ' · 任务列表' : '任务列表';
    $('#btnAddTask').disabled = false;
    await loadTasks();
}

async function createSchedule() {
    const name = prompt('请输入日程表名称：');
    if (!name) return;
    try {
        const created = await API.createSchedule({name: name.trim()});
        toast('已创建日程表');
        await loadSchedules();
        await selectSchedule(created.id);
    } catch (e) {
        toast(e.message, true);
    }
}

async function activateSchedule(id) {
    try {
        await API.activateSchedule(id);
        toast('已切换运行日程表');
        await loadSchedules();
        await selectSchedule(id);
    } catch (e) {
        toast(e.message, true);
    }
}

async function deleteSchedule(id) {
    const sch = state.schedules.find((s) => s.id === id);
    if (!confirm(`确定删除日程表「${sch ? sch.name : id}」？其下任务将变为未分组。`)) return;
    try {
        await API.deleteSchedule(id);
        toast('已删除日程表');
        await loadSchedules();
        if (state.schedules.length > 0) {
            await selectSchedule(state.schedules[0].id);
        } else {
            state.currentScheduleId = null;
            state.tasks = [];
            $('#currentScheduleName').textContent = '任务列表';
            $('#btnAddTask').disabled = true;
            renderTasks();
        }
    } catch (e) {
        toast(e.message, true);
    }
}

/* ---------------- 任务列表（左栏） ---------------- */
async function loadTasks() {
    if (!state.currentScheduleId) {
        state.tasks = [];
        renderTasks();
        return;
    }
    state.tasks = await API.listTasksBySchedule(state.currentScheduleId);
    renderTasks();
}

function statusClass(task) {
    if (!task.lastStatus) return 'idle';
    return task.lastStatus === 'SUCCESS' ? 'ok' : 'fail';
}

function renderTasks() {
    const ul = $('#taskList');
    ul.innerHTML = '';
    if (state.tasks.length === 0) {
        $('#taskListEmpty').classList.remove('hidden');
        return;
    }
    $('#taskListEmpty').classList.add('hidden');
    state.tasks.forEach((t) => {
        const li = document.createElement('li');
        li.className = 'task-item' + (t.id === state.selectedTaskId ? ' selected' : '');
        li.dataset.id = t.id;
        li.draggable = true;
        li.innerHTML = `
            <span class="drag-handle">⋮⋮</span>
            <span class="task-status ${statusClass(t)}"></span>
            <span class="task-name">${escapeHtml(t.name)}</span>
            <span class="task-type-badge">${escapeHtml(t.typeDisplayName)}</span>
            <span class="task-toggle">
                <label class="switch-mini">
                    <input type="checkbox" ${t.enabled ? 'checked' : ''} data-toggle="${t.id}">
                    <span class="slider-mini"></span>
                </label>
            </span>
        `;
        li.addEventListener('click', (e) => {
            if (e.target.closest('[data-toggle]')) return;
            selectTask(t.id);
        });
        li.querySelector('[data-toggle]').addEventListener('change', async (e) => {
            e.stopPropagation();
            try {
                await API.toggleTask(t.id, e.target.checked);
                t.enabled = e.target.checked;
                toast('已更新启用状态');
            } catch (err) {
                e.target.checked = !e.target.checked;
                toast(err.message, true);
            }
        });
        ul.appendChild(li);
    });
    setupDragSort(ul);
}

/* 原生拖拽排序 */
function setupDragSort(ul) {
    let dragEl = null;
    ul.addEventListener('dragstart', (e) => {
        dragEl = e.target.closest('.task-item');
        dragEl.classList.add('dragging');
        e.dataTransfer.effectAllowed = 'move';
    });
    ul.addEventListener('dragover', (e) => {
        e.preventDefault();
        const after = getDragAfter(ul, e.clientY);
        if (!dragEl) return;
        if (after == null) ul.appendChild(dragEl);
        else ul.insertBefore(dragEl, after);
    });
    ul.addEventListener('dragend', async () => {
        if (dragEl) dragEl.classList.remove('dragging');
        const orderedIds = $$('.task-item', ul).map((el) => Number(el.dataset.id));
        try {
            await API.reorderTasks(orderedIds);
        } catch (e) {
            toast('排序保存失败：' + e.message, true);
            await loadTasks();
        }
    });
}
function getDragAfter(ul, y) {
    const items = $$('.task-item:not(.dragging)', ul);
    let closest = {offset: -Infinity, el: null};
    items.forEach((el) => {
        const box = el.getBoundingClientRect();
        const offset = y - box.top - box.height / 2;
        if (offset < 0 && offset > closest.offset) closest = {offset, el};
    });
    return closest.el;
}

/* ---------------- 任务详情（右栏） ---------------- */
async function selectTask(id) {
    state.selectedTaskId = id;
    renderTasks();
    const task = state.tasks.find((t) => t.id === id);
    $('#detailPlaceholder').classList.add('hidden');
    $('#detailForm').classList.remove('hidden');
    $('#detailId').value = task.id;
    $('#f_name').value = task.name;
    $('#f_command').value = task.command;
    $('#f_workingDir').value = task.workingDir || '';
    $('#f_typeCode').value = task.typeCode;
    $('#f_timeoutSeconds').value = task.timeoutSeconds || '';
    $('#f_enabled').checked = task.enabled;
    $('#f_remark').value = task.remark || '';
    $('#detailNext').textContent = '下次执行：' + fmtTime(task.nextExecutionAt);
    $('#detailLast').textContent = '最近执行：' + fmtTime(task.lastExecutedAt) + (task.lastStatus ? `（${task.lastStatus === 'SUCCESS' ? '成功' : '失败'}）` : '');
    renderLastResult(task);
    renderConfigFields(task.typeCode, task.config || {});
}

function renderLastResult(task) {
    const box = $('#resultBox');
    if (!task.lastExecutedAt || !task.lastStatus) {
        box.classList.add('hidden');
        return;
    }
    box.classList.remove('hidden');
    const statusText = task.lastStatus === 'SUCCESS' ? '成功'
            : task.lastStatus === 'TIMEOUT' ? '超时' : '失败';
    const badge = $('#resultBadge');
    badge.textContent = statusText + (task.lastExitCode != null ? ` · 退出码 ${task.lastExitCode}` : '');
    badge.className = 'result-badge ' + (
        task.lastStatus === 'SUCCESS' ? 'ok' : 'fail');
    $('#resultMeta').textContent = fmtTime(task.lastExecutedAt);

    const output = [];
    if (task.lastStdout) output.push('--- stdout ---\n' + task.lastStdout);
    if (task.lastStderr) output.push('--- stderr ---\n' + task.lastStderr);
    const text = output.join('\n\n');
    $('#resultOutput').textContent = text || '（无输出）';
    $('#resultOutput').classList.toggle('has-error', task.lastStatus !== 'SUCCESS');
}

function renderConfigFields(typeCode, config) {
    const box = $('#configFields');
    box.innerHTML = '';
    const type = state.taskTypes.find((t) => t.typeCode === typeCode);
    if (!type || !type.configSchema) return;
    type.configSchema.forEach((field) => {
        const val = config && config[field.name] != null ? config[field.name] : field.default != null ? field.default : '';
        const label = document.createElement('label');
        label.className = 'field';
        label.innerHTML = `<span>${escapeHtml(field.label)}${field.required ? ' *' : ''}</span>`;
        let input;
        if (field.type === 'select') {
            input = document.createElement('select');
            (field.options || []).forEach((opt) => {
                const o = document.createElement('option');
                o.value = opt.value;
                o.textContent = opt.label;
                input.appendChild(o);
            });
            input.value = val;
        } else {
            input = document.createElement('input');
            input.type = field.type === 'number' ? 'number'
                    : field.type === 'time' ? 'time' : 'text';
            if (field.type === 'number') input.min = field.min ?? 0;
            if (field.max != null) input.max = field.max;
            input.value = val;
        }
        input.dataset.config = field.name;
        label.appendChild(input);
        box.appendChild(label);
    });
}

function collectConfig() {
    const cfg = {};
    $$('#configFields [data-config]').forEach((el) => {
        const name = el.dataset.config;
        if (el.tagName === 'SELECT') {
            cfg[name] = el.value;
        } else if (el.type === 'number') {
            cfg[name] = el.value === '' ? null : Number(el.value);
        } else {
            cfg[name] = el.value;
        }
    });
    return cfg;
}

async function saveDetail() {
    const id = Number($('#detailId').value);
    const payload = {
        name: $('#f_name').value.trim(),
        command: $('#f_command').value,
        workingDir: $('#f_workingDir').value.trim() || null,
        typeCode: $('#f_typeCode').value,
        config: collectConfig(),
        enabled: $('#f_enabled').checked,
        timeoutSeconds: $('#f_timeoutSeconds').value ? Number($('#f_timeoutSeconds').value) : null,
        remark: $('#f_remark').value.trim() || null,
        scheduleId: state.currentScheduleId,
    };
    try {
        const updated = await API.updateTask(id, payload);
        toast('已保存');
        await loadTasks();
        selectTask(updated.id);
    } catch (e) {
        toast(e.message, true);
    }
}

async function runTask() {
    const id = Number($('#detailId').value);
    toast('正在执行…');
    try {
        const log = await API.executeTask(id);
        await loadTasks();
        selectTask(id);
        const task = state.tasks.find((t) => t.id === id);
        renderLastResult(task);
        $('#resultOutput').scrollIntoView({behavior: 'smooth', block: 'nearest'});
    } catch (e) {
        toast(e.message, true);
    }
}

async function viewHistory() {
    const id = Number($('#detailId').value);
    try {
        const logs = await API.taskLogs(id, 20);
        if (!logs || logs.length === 0) {
            toast('暂无历史记录', true);
            return;
        }
        const text = logs.map((l, i) => {
            const head = `#${logs.length - i}  ${fmtTime(l.startedAt)}  [${l.status}]  退出码 ${l.exitCode}`;
            const out = [
                l.stdout ? `--- stdout ---\n${l.stdout}` : '',
                l.stderr ? `--- stderr ---\n${l.stderr}` : ''
            ].filter(Boolean).join('\n');
            return head + (out ? '\n' + out : '');
        }).join('\n\n' + '─'.repeat(40) + '\n\n');
        $('#resultBox').classList.remove('hidden');
        $('#resultOutput').textContent = text;
        $('#resultOutput').classList.remove('has-error');
        $('#resultOutput').scrollIntoView({behavior: 'smooth', block: 'nearest'});
    } catch (e) {
        toast(e.message, true);
    }
}

async function deleteTask() {    const id = Number($('#detailId').value);
    if (!confirm('确定删除该任务？')) return;
    try {
        await API.deleteTask(id);
        toast('已删除任务');
        state.selectedTaskId = null;
        $('#detailForm').classList.add('hidden');
        $('#detailPlaceholder').classList.remove('hidden');
        await loadTasks();
    } catch (e) {
        toast(e.message, true);
    }
}

/* ---------------- 新建任务弹窗 ---------------- */
async function openCreateTaskModal() {
    if (!state.currentScheduleId) {
        toast('请先选择一个日程表', true);
        return;
    }
    const modal = $('#modalOverlay');
    $('#modalTitle').textContent = '新建任务';
    const body = $('#modalBody');
    body.innerHTML = `
        <form id="createForm" class="modal-form">
            <label class="field"><span>名称</span><input type="text" id="c_name" required maxlength="100"></label>
            <label class="field"><span>命令</span><textarea id="c_command" rows="2" required></textarea></label>
            <label class="field"><span>工作目录</span><input type="text" id="c_workingDir" placeholder="可选"></label>
            <label class="field"><span>类型</span><select id="c_typeCode"></select></label>
            <div id="c_configFields" class="config-fields"></div>
            <div class="field-row">
                <label class="field"><span>超时(秒)</span><input type="number" id="c_timeoutSeconds" min="1" max="86400"></label>
                <label class="field checkbox"><input type="checkbox" id="c_enabled" checked><span>启用</span></label>
            </div>
            <label class="field"><span>备注(Hint)</span><textarea id="c_remark" rows="2" maxlength="255"></textarea></label>
            <div class="modal-actions">
                <button type="button" class="btn-ghost" id="modalCancel">取消</button>
                <button type="submit" class="btn-primary">创建</button>
            </div>
        </form>
    `;
    const sel = $('#c_typeCode');
    state.taskTypes.forEach((t) => {
        const o = document.createElement('option');
        o.value = t.typeCode;
        o.textContent = t.typeDisplayName;
        sel.appendChild(o);
    });
    function renderCConfig() {
        const box = $('#c_configFields');
        box.innerHTML = '';
        const type = state.taskTypes.find((t) => t.typeCode === sel.value);
        if (!type || !type.configSchema) return;
        type.configSchema.forEach((field) => {
            const label = document.createElement('label');
            label.className = 'field';
            label.innerHTML = `<span>${escapeHtml(field.label)}${field.required ? ' *' : ''}</span>`;
            let input;
            if (field.type === 'select') {
                input = document.createElement('select');
                (field.options || []).forEach((opt) => {
                    const o = document.createElement('option');
                    o.value = opt.value;
                    o.textContent = opt.label;
                    input.appendChild(o);
                });
                input.value = field.default != null ? field.default : '';
            } else {
                input = document.createElement('input');
                input.type = field.type === 'number' ? 'number'
                        : field.type === 'time' ? 'time' : 'text';
                if (field.type === 'number') input.min = field.min ?? 0;
                if (field.max != null) input.max = field.max;
                input.value = field.default != null ? field.default : '';
            }
            input.dataset.config = field.name;
            label.appendChild(input);
            box.appendChild(label);
        });
    }
    sel.addEventListener('change', renderCConfig);
    renderCConfig();

    $('#modalCancel').addEventListener('click', closeModal);
    $('#createForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        const cfg = {};
        $$('#c_configFields [data-config]').forEach((el) => {
            cfg[el.dataset.config] = el.type === 'number' ? (el.value === '' ? null : Number(el.value)) : el.value;
        });
        const payload = {
            name: $('#c_name').value.trim(),
            command: $('#c_command').value,
            workingDir: $('#c_workingDir').value.trim() || null,
            typeCode: sel.value,
            config: cfg,
            enabled: $('#c_enabled').checked,
            timeoutSeconds: $('#c_timeoutSeconds').value ? Number($('#c_timeoutSeconds').value) : null,
            remark: $('#c_remark').value.trim() || null,
            scheduleId: state.currentScheduleId,
        };
        try {
            await API.createTask(payload);
            closeModal();
            toast('已创建任务');
            await loadTasks();
        } catch (err) {
            toast(err.message, true);
        }
    });
    modal.classList.remove('hidden');
}

function closeModal() {
    $('#modalOverlay').classList.add('hidden');
}

/* ---------------- 调度状态 ---------------- */
async function loadSchedulerState() {
    try {
        const info = await API.systemInfo();
        const el = $('#schedulerState');
        if (info.globallyPaused) {
            el.textContent = '已暂停';
            el.className = 'state-pill paused';
        } else {
            el.textContent = `运行中 · ${info.scheduledCount} 个任务`;
            el.className = 'state-pill running';
        }
    } catch (_) {
        $('#schedulerState').textContent = '—';
    }
}

/* ---------------- 引导 ---------------- */
async function init() {
    setupModeSwitch();
    $('#modalClose').addEventListener('click', closeModal);
    $('#modalOverlay').addEventListener('click', (e) => {
        if (e.target === $('#modalOverlay')) closeModal();
    });
    $('#btnAddSchedule').addEventListener('click', createSchedule);
    $('#btnAddTask').addEventListener('click', openCreateTaskModal);
    $('#detailForm').addEventListener('submit', (e) => {
        e.preventDefault();
        saveDetail();
    });
    $('#btnRunTask').addEventListener('click', runTask);
    $('#btnDeleteTask').addEventListener('click', deleteTask);
    $('#btnViewHistory').addEventListener('click', viewHistory);

    // 双击日程表项可切换为运行中的日程表；右键删除
    $('#scheduleList').addEventListener('dblclick', (e) => {
        const item = e.target.closest('.schedule-item');
        if (item) activateSchedule(Number(item.dataset.id));
    });
    $('#scheduleList').addEventListener('contextmenu', (e) => {
        const item = e.target.closest('.schedule-item');
        if (item) {
            e.preventDefault();
            deleteSchedule(Number(item.dataset.id));
        }
    });

    try {
        state.taskTypes = await API.taskTypes();
        const sel = $('#f_typeCode');
        state.taskTypes.forEach((t) => {
            const o = document.createElement('option');
            o.value = t.typeCode;
            o.textContent = t.typeDisplayName;
            sel.appendChild(o);
        });
    } catch (e) {
        toast('加载任务类型失败：' + e.message, true);
    }

    // 日程表：默认加载并选中运行中（active）的那个
    try {
        const cur = await API.currentSchedule();
        await loadSchedules();
        if (state.schedules.length > 0) {
            await selectSchedule(cur.id ?? state.schedules[0].id);
        }
    } catch (e) {
        toast('加载日程表失败：' + e.message, true);
    }

    await loadSchedulerState();
    // 周期性刷新运行时间等派生信息
    setInterval(loadSchedulerState, 15000);
}

document.addEventListener('DOMContentLoaded', init);
