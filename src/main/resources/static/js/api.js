// API 封装层：隔离后端接口细节，方便后续替换为 Vue+Vite 时的数据层。
const API = (() => {
    const base = '/api';

    async function request(method, path, body) {
        const opts = {
            method,
            headers: {'Content-Type': 'application/json'},
        };
        if (body !== undefined) opts.body = JSON.stringify(body);
        const res = await fetch(base + path, opts);
        if (res.status === 204) return null;
        const text = await res.text();
        const data = text ? JSON.parse(text) : null;
        if (!res.ok) {
            const msg = (data && (data.message || data.error)) || `请求失败 (${res.status})`;
            throw new Error(msg);
        }
        return data;
    }

    return {
        // 任务类型
        taskTypes: () => request('GET', '/task-types'),

        // 日程表
        listSchedules: () => request('GET', '/schedules'),
        currentSchedule: () => request('GET', '/schedules/current'),
        createSchedule: (payload) => request('POST', '/schedules', payload),
        updateSchedule: (id, payload) => request('PUT', `/schedules/${id}`, payload),
        activateSchedule: (id) => request('POST', `/schedules/${id}/activate`),
        deleteSchedule: (id) => request('DELETE', `/schedules/${id}`),

        // 任务
        listTasksBySchedule: (scheduleId) => request('GET', `/tasks?scheduleId=${scheduleId}`),
        listAllTasks: () => request('GET', '/tasks'),
        getTask: (id) => request('GET', `/tasks/${id}`),
        createTask: (payload) => request('POST', '/tasks', payload),
        updateTask: (id, payload) => request('PUT', `/tasks/${id}`, payload),
        deleteTask: (id) => request('DELETE', `/tasks/${id}`),
        toggleTask: (id, enabled) => request('PATCH', `/tasks/${id}/toggle?enabled=${enabled}`),
        reorderTasks: (orderedIds) => request('PUT', '/tasks/sort', {orderedIds}),
        executeTask: (id) => request('POST', `/tasks/${id}/execute`),
        taskLogs: (id, limit = 20) => request('GET', `/tasks/${id}/logs?limit=${limit}`),

        // 系统
        systemInfo: () => request('GET', '/system/info'),
        pauseScheduler: () => request('POST', '/system/scheduler/pause'),
        resumeScheduler: () => request('POST', '/system/scheduler/resume'),
        checkPath: (path) => request('POST', '/system/check-path', {path}),
    };
})();
