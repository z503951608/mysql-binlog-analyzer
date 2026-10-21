(function () {
    'use strict';

    var ROW_H = 28;
    var TERMINAL = { DONE: true, FAILED: true, CANCELLED: true };

    var state = {
        connected: false,
        connection: null,
        mode: 'online',
        analyzing: false,
        uploading: false,
        jobId: null,
        job: null,
        entries: [],
        redoRows: [],
        undoRows: [],
        searchTokens: [],
        searchRegex: null,
        searchTimer: null,
        binlogFiles: [],
        uploads: [],
        pollTimer: null,
        pollErrors: 0,
        menuTable: null,
        detailRow: null
    };

    function $(id) {
        return document.getElementById(id);
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function escapeRegExp(value) {
        return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }

    function highlightText(text) {
        var value = String(text == null ? '' : text);
        var regex = state.searchRegex;
        if (!regex) {
            return escapeHtml(value);
        }
        regex.lastIndex = 0;
        var html = '';
        var last = 0;
        var match;
        while ((match = regex.exec(value)) !== null) {
            if (!match[0]) {
                regex.lastIndex++;
                continue;
            }
            html += escapeHtml(value.slice(last, match.index))
                + '<mark>' + escapeHtml(match[0]) + '</mark>';
            last = match.index + match[0].length;
        }
        return html + escapeHtml(value.slice(last));
    }

    function pad2(value) {
        return value < 10 ? '0' + value : String(value);
    }

    function formatTimestamp(ms) {
        var d = new Date(ms);
        return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
            + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
    }

    function formatDetail(ms) {
        var d = new Date(ms);
        return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
            + '_' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
    }

    function formatBytes(bytes) {
        if (!bytes || bytes < 0) {
            return '0 B';
        }
        var units = ['B', 'KB', 'MB', 'GB', 'TB'];
        var index = 0;
        var value = bytes;
        while (value >= 1024 && index < units.length - 1) {
            value = value / 1024;
            index++;
        }
        return (index === 0 ? value : value.toFixed(value >= 100 ? 0 : 1)) + ' ' + units[index];
    }

    function formatElapsed(ms) {
        if (!ms || ms < 0) {
            return '0.0 秒';
        }
        if (ms < 60000) {
            return (ms / 1000).toFixed(1) + ' 秒';
        }
        var minutes = Math.floor(ms / 60000);
        var seconds = Math.round((ms % 60000) / 1000);
        return minutes + ' 分 ' + seconds + ' 秒';
    }

    function toast(message, kind, timeout) {
        var container = $('toastContainer');
        var el = document.createElement('div');
        el.className = 'toast ' + (kind || 'info');
        el.textContent = message;
        container.appendChild(el);
        var life = timeout || (kind === 'error' ? 6200 : 3800);
        var timer = setTimeout(function () {
            el.style.transition = 'opacity .18s, transform .18s';
            el.style.opacity = '0';
            el.style.transform = 'translateX(8px)';
            setTimeout(function () {
                if (el.parentNode) {
                    el.parentNode.removeChild(el);
                }
            }, 200);
        }, life);
        el.addEventListener('click', function () {
            clearTimeout(timer);
            if (el.parentNode) {
                el.parentNode.removeChild(el);
            }
        });
    }

    function setNotice(text, kind) {
        var box = $('notice');
        if (!text) {
            box.hidden = true;
            box.textContent = '';
            box.className = 'notice';
            return;
        }
        box.hidden = false;
        box.className = 'notice ' + (kind || 'info');
        box.textContent = text;
    }

    async function api(path, options) {
        var opts = options || {};
        var init = { method: opts.method || 'GET', credentials: 'same-origin', headers: opts.headers || {} };
        if (opts.body !== undefined && opts.body !== null) {
            if (typeof opts.body === 'string' || opts.body instanceof FormData) {
                init.body = opts.body;
            } else {
                init.headers['Content-Type'] = 'application/json';
                init.body = JSON.stringify(opts.body);
            }
        }
        var response;
        try {
            response = await fetch(path, init);
        } catch (e) {
            throw new Error('无法连接 Web 服务，请确认程序仍在运行');
        }
        if (!response.ok) {
            var message = '请求失败（HTTP ' + response.status + '）';
            try {
                var data = await response.json();
                if (data && data.message) {
                    message = data.message;
                }
            } catch (ignored) {
                // not a JSON error body
            }
            var error = new Error(message);
            error.status = response.status;
            throw error;
        }
        if (response.status === 204) {
            return null;
        }
        var type = response.headers.get('content-type') || '';
        if (type.indexOf('application/json') >= 0) {
            return response.json();
        }
        return response;
    }

    function setBusy(button, busy, busyText) {
        if (!button) {
            return;
        }
        if (busy) {
            if (!button.dataset.originalText) {
                button.dataset.originalText = button.textContent;
            }
            button.disabled = true;
            button.classList.add('loading');
            button.textContent = busyText || '处理中…';
        } else {
            button.disabled = false;
            button.classList.remove('loading');
            if (button.dataset.originalText) {
                button.textContent = button.dataset.originalText;
                delete button.dataset.originalText;
            }
        }
    }

    function headerState(kind, text) {
        var dot = $('statusDot');
        dot.className = 'status-dot' + (kind ? ' ' + kind : '');
        $('statusText').textContent = text;
    }

    function connectionLabel() {
        if (!state.connected || !state.connection) {
            return '未连接数据库';
        }
        var conn = state.connection;
        return conn.host + ':' + conn.port + ' · MySQL ' + conn.serverVersion;
    }

    function showConnected(info) {
        state.connected = true;
        state.connection = info;
        headerState('connected', connectionLabel());
        $('btnDisconnect').hidden = false;
        $('dictHint').textContent = '共 ' + info.schemas.length + ' 个数据库，选择数据库和表后可开始分析';
        $('schema').disabled = false;

        var schemaSelect = $('schema');
        var previous = schemaSelect.value;
        var saved = previous || localStorage.getItem('binlog.schema') || '';
        var options = ['<option value="">请选择数据库</option>'];
        info.schemas.forEach(function (name) {
            options.push('<option value="' + escapeHtml(name) + '">' + escapeHtml(name) + '</option>');
        });
        schemaSelect.innerHTML = options.join('');

        var selected = '';
        if (saved && info.schemas.indexOf(saved) >= 0) {
            selected = saved;
        } else {
            var system = ['information_schema', 'mysql', 'performance_schema', 'sys'];
            for (var i = 0; i < info.schemas.length; i++) {
                if (system.indexOf(info.schemas[i].toLowerCase()) < 0) {
                    selected = info.schemas[i];
                    break;
                }
            }
            if (!selected && info.schemas.length) {
                selected = info.schemas[0];
            }
        }
        schemaSelect.value = selected;
        updateAnalyzeButton();
        if (selected) {
            loadTables(selected);
        }
    }

    function resetConnectionUi() {
        state.connected = false;
        state.connection = null;
        state.binlogFiles = [];
        headerState('', '未连接数据库');
        $('btnDisconnect').hidden = true;
        $('schema').disabled = true;
        $('schema').innerHTML = '<option value="">请先获取数据字典</option>';
        $('table').disabled = true;
        $('table').innerHTML = '<option value="">全部表</option>';
        $('startFile').disabled = true;
        $('startFile').innerHTML = '<option value="">请先获取数据字典</option>';
        $('btnOpenDownload').disabled = true;
        $('dictHint').textContent = '等待获取数据字典…';
        updateAnalyzeButton();
    }

    async function loadTables(schema) {
        var tableSelect = $('table');
        tableSelect.disabled = true;
        tableSelect.innerHTML = '<option value="">全部表</option>';
        try {
            var tables = await api('/api/dictionary/tables?schema=' + encodeURIComponent(schema));
            var options = ['<option value="">全部表</option>'];
            tables.forEach(function (name) {
                options.push('<option value="' + escapeHtml(name) + '">' + escapeHtml(name) + '</option>');
            });
            tableSelect.innerHTML = options.join('');
            var savedTable = localStorage.getItem('binlog.table.' + schema);
            if (savedTable && tables.indexOf(savedTable) >= 0) {
                tableSelect.value = savedTable;
            }
            tableSelect.disabled = false;
        } catch (e) {
            tableSelect.innerHTML = '<option value="">读取数据表失败</option>';
            toast('读取数据表失败：' + e.message, 'error');
        }
        updateAnalyzeButton();
    }

    async function loadBinlogs() {
        var select = $('startFile');
        select.disabled = true;
        $('btnOpenDownload').disabled = true;
        state.binlogFiles = [];
        try {
            var files = await api('/api/binlogs');
            state.binlogFiles = files || [];
            if (!state.binlogFiles.length) {
                select.innerHTML = '<option value="">服务器没有可用的 binlog</option>';
                toast('服务器上没有可用的 binlog，请确认已开启 log_bin', 'warn', 6000);
                updateAnalyzeButton();
                return;
            }
            select.innerHTML = state.binlogFiles.map(function (file) {
                return '<option value="' + escapeHtml(file.name) + '">' + escapeHtml(file.name)
                    + ' — ' + formatBytes(file.size) + (file.encrypted ? '（已加密）' : '') + '</option>';
            }).join('');
            select.value = state.binlogFiles[state.binlogFiles.length - 1].name;
            select.disabled = false;
            $('btnOpenDownload').disabled = false;
        } catch (e) {
            select.innerHTML = '<option value="">读取 binlog 列表失败</option>';
            toast('读取 binlog 列表失败：' + e.message, 'error', 7000);
        }
        updateAnalyzeButton();
    }

    async function loadUploads(selectId) {
        var select = $('uploadSelect');
        try {
            var uploads = await api('/api/uploads');
            state.uploads = uploads || [];
            if (!state.uploads.length) {
                select.innerHTML = '<option value="">暂无已上传文件，请先上传</option>';
                updateAnalyzeButton();
                return;
            }
            select.innerHTML = state.uploads.map(function (file) {
                return '<option value="' + escapeHtml(file.id) + '">' + escapeHtml(file.originalName)
                    + '（' + formatBytes(file.size) + '）</option>';
            }).join('');
            var wanted = selectId || select.value;
            if (wanted && state.uploads.some(function (file) { return file.id === wanted; })) {
                select.value = wanted;
            } else {
                select.value = state.uploads[0].id;
            }
        } catch (e) {
            select.innerHTML = '<option value="">读取已上传文件失败</option>';
            toast('读取已上传文件失败：' + e.message, 'error');
        }
        updateAnalyzeButton();
    }

    function updateAnalyzeButton() {
        var button = $('btnAnalyze');
        var hasSchema = !!$('schema').value;
        var ready;
        if (state.mode === 'online') {
            ready = state.binlogFiles.length > 0;
        } else {
            ready = !!$('uploadSelect').value;
        }
        button.disabled = state.analyzing || state.uploading || !state.connected || !hasSchema || !ready;
        button.textContent = state.mode === 'online' ? '分析日志' : '离线分析';
    }

    function setMode(mode) {
        if (state.analyzing) {
            toast('分析进行中，请等待结束或先取消分析', 'warn');
            return;
        }
        state.mode = mode;
        var buttons = document.querySelectorAll('.mode-btn');
        for (var i = 0; i < buttons.length; i++) {
            var active = buttons[i].getAttribute('data-mode') === mode;
            buttons[i].classList.toggle('active', active);
            buttons[i].setAttribute('aria-selected', active ? 'true' : 'false');
        }
        $('onlineRow').hidden = mode !== 'online';
        $('offlineBox').hidden = mode !== 'offline';
        updateAnalyzeButton();
    }

    async function connect(event) {
        if (event) {
            event.preventDefault();
        }
        var host = $('host').value.trim();
        var port = parseInt($('port').value, 10);
        var user = $('user').value.trim();
        var password = $('password').value;
        if (!host) {
            toast('请输入主机名或 IP 地址', 'warn');
            $('host').focus();
            return;
        }
        if (!port || port < 1 || port > 65535) {
            toast('端口号不合法', 'warn');
            $('port').focus();
            return;
        }
        if (!user) {
            toast('请输入数据库用户', 'warn');
            $('user').focus();
            return;
        }

        var button = $('btnConnect');
        setBusy(button, true, '正在连接…');
        hideWarnings();
        setNotice('');
        try {
            var info = await api('/api/dictionary', {
                method: 'POST',
                body: { host: host, port: port, user: user, password: password }
            });
            localStorage.setItem('binlog.host', host);
            localStorage.setItem('binlog.port', String(port));
            localStorage.setItem('binlog.user', user);
            showConnected(info);
            toast('成功获取数据字典！', 'ok');
            await Promise.all([loadBinlogs(), loadUploads()]);
        } catch (e) {
            resetConnectionUi();
            toast(e.message, 'error', 7000);
        } finally {
            setBusy(button, false);
        }
    }

    async function disconnect() {
        if (state.analyzing) {
            toast('分析进行中，请先取消分析', 'warn');
            return;
        }
        try {
            await api('/api/dictionary/disconnect', { method: 'POST' });
        } catch (ignored) {
            // session may already be gone
        }
        resetConnectionUi();
        toast('已断开数据库连接', 'info');
    }

    function buildAnalyzeRequest() {
        var online = state.mode === 'online';
        return {
            online: online,
            schema: $('schema').value,
            table: $('table').value || '',
            startFile: online ? $('startFile').value : null,
            analyzeToLatest: online ? $('analyzeToLatest').checked : false,
            startTime: $('startTime').value || null,
            endTime: $('endTime').value || null,
            operation: $('operation').value,
            keyword: $('keyword').value.trim(),
            display: $('display').value,
            charset: $('charset').value,
            uploadId: online ? null : $('uploadSelect').value
        };
    }

    async function startAnalysis() {
        if (state.analyzing) {
            return;
        }
        var request = buildAnalyzeRequest();
        if (!request.schema) {
            toast('请先选择要分析的数据库', 'warn');
            return;
        }
        if (request.online && !state.binlogFiles.length) {
            toast('暂无可用 binlog，请刷新日志列表或改用离线分析', 'warn');
            return;
        }
        if (!request.online && !request.uploadId) {
            toast('请先上传并选择离线 binlog 文件', 'warn');
            return;
        }
        if (request.startTime && request.endTime && request.startTime > request.endTime) {
            toast('开始时间不能晚于结束时间', 'warn');
            return;
        }

        state.analyzing = true;
        state.jobId = null;
        state.job = null;
        state.entries = [];
        resetResults();
        hideWarnings();
        setNotice('');
        setProgress({ progress: 0, phase: '正在创建分析任务…', eventsRead: 0 });
        $('btnCancel').hidden = false;
        $('btnCancel').disabled = false;
        headerState('busy', '正在分析 binlog…');
        updateAnalyzeButton();

        try {
            var view = await api('/api/analysis', { method: 'POST', body: request });
            state.jobId = view.id;
            state.job = view;
            renderProgress(view);
            pollJob(view.id);
        } catch (e) {
            state.analyzing = false;
            $('btnCancel').hidden = true;
            headerState(state.connected ? 'connected' : '', connectionLabel());
            setNotice(e.message, 'error');
            toast(e.message, 'error', 7000);
            updateAnalyzeButton();
        }
    }

    function pollJob(id) {
        clearTimeout(state.pollTimer);
        var tick = async function () {
            if (state.jobId !== id) {
                return;
            }
            try {
                var view = await api('/api/analysis/' + encodeURIComponent(id));
                if (state.jobId !== id) {
                    return;
                }
                state.pollErrors = 0;
                state.job = view;
                renderProgress(view);
                if (TERMINAL[view.status]) {
                    finishAnalysis(view);
                    return;
                }
            } catch (e) {
                state.pollErrors++;
                if (e.status === 404 || state.pollErrors >= 5) {
                    state.analyzing = false;
                    $('btnCancel').hidden = true;
                    headerState(state.connected ? 'connected' : '', connectionLabel());
                    setNotice('获取分析进度失败：' + e.message, 'error');
                    toast('获取分析进度失败：' + e.message, 'error', 7000);
                    updateAnalyzeButton();
                    return;
                }
            }
            state.pollTimer = setTimeout(tick, 700);
        };
        state.pollTimer = setTimeout(tick, 300);
    }

    function finishAnalysis(view) {
        state.analyzing = false;
        $('btnCancel').hidden = true;
        $('btnCancel').disabled = false;
        headerState(state.connected ? 'connected' : '', connectionLabel());
        state.entries = view.entries || [];
        renderResults();
        renderWarnings(view.warnings || []);
        updateAnalyzeButton();

        if (view.status === 'DONE') {
            var message = '分析完成：共生成 ' + state.entries.length + ' 条记录';
            if (view.stopReason) {
                message += '。结束原因：' + view.stopReason;
            }
            if (view.truncated) {
                message += '。已达到记录上限，结果可能不完整';
            }
            setNotice(message, view.truncated ? 'warn' : 'ok');
            toast('成功获取 ' + state.entries.length + ' 条 redo SQL（重做语句）和 '
                + state.entries.length + ' 条 undo SQL（回滚语句）！', 'ok', 5000);
        } else if (view.status === 'CANCELLED') {
            setNotice('分析已取消，已显示取消前生成的 ' + state.entries.length + ' 条记录。', 'warn');
            toast('分析已取消', 'warn');
        } else {
            setNotice(view.error || '分析失败', 'error');
            toast(view.error || '分析失败', 'error', 8000);
        }
    }

    async function cancelAnalysis() {
        if (!state.jobId) {
            return;
        }
        var button = $('btnCancel');
        button.disabled = true;
        try {
            await api('/api/analysis/' + encodeURIComponent(state.jobId) + '/cancel', { method: 'POST' });
            toast('已请求取消，正在结束分析…', 'warn');
        } catch (e) {
            toast(e.message, 'error');
        } finally {
            button.disabled = false;
        }
    }

    function setProgress(view) {
        var box = $('progressBox');
        box.hidden = false;
        var progress = Math.max(0, Math.min(100, view.progress || 0));
        $('progressBar').style.width = progress + '%';
        $('progressPercent').textContent = progress + '%';
        $('progressPhase').textContent = view.phase || '准备中…';
        if (view.currentFile) {
            $('progressFile').textContent = '当前日志：' + view.currentFile;
        } else if (!view.online) {
            $('progressFile').textContent = '';
        } else {
            $('progressFile').textContent = '';
        }
        var meta = '已读取 ' + (view.eventsRead || 0).toLocaleString() + ' 个事件';
        if (typeof view.elapsedMs === 'number' && view.elapsedMs > 0) {
            meta += ' · 已用时 ' + formatElapsed(view.elapsedMs);
        }
        $('progressEvents').textContent = meta;
    }

    function renderProgress(view) {
        setProgress(view);
        var statusText = view.status === 'DONE' ? '分析完成'
            : view.status === 'FAILED' ? '分析失败'
            : view.status === 'CANCELLED' ? '已取消分析' : '正在分析 binlog…';
        headerState(state.connected ? 'connected' : '', statusText);
        if (view.phase) {
            $('progressPhase').textContent = view.phase;
        }
    }

    function hideWarnings() {
        $('warningsBox').hidden = true;
        $('warningList').innerHTML = '';
        $('warningCount').textContent = '';
    }

    function renderWarnings(warnings) {
        if (!warnings || !warnings.length) {
            hideWarnings();
            return;
        }
        $('warningsBox').hidden = false;
        $('warningCount').textContent = '（' + warnings.length + ' 条）';
        var list = $('warningList');
        list.innerHTML = '';
        warnings.forEach(function (text) {
            var li = document.createElement('li');
            li.textContent = text;
            list.appendChild(li);
        });
    }

    function VirtualTable(root, kind) {
        this.root = root;
        this.kind = kind;
        this.body = root.querySelector('.vt-body');
        this.spacer = root.querySelector('.vt-spacer');
        this.rowsEl = root.querySelector('.vt-rows');
        this.empty = root.querySelector('.vt-empty');
        this.emptyDefault = this.empty.textContent;
        this.rows = [];
        this.total = 0;
        this.selected = new Set();
        this.lastIndex = -1;
        this.onSelectionChange = null;
        var self = this;

        this.body.addEventListener('scroll', function () {
            self.paint();
        });
        this.rowsEl.addEventListener('click', function (event) {
            self.handleClick(event);
        });
        this.rowsEl.addEventListener('dblclick', function (event) {
            var el = event.target.closest('.vt-row');
            if (!el) {
                return;
            }
            var index = parseInt(el.getAttribute('data-index'), 10);
            var row = self.rows[index];
            if (row) {
                openDetail(row);
            }
        });
        this.rowsEl.addEventListener('contextmenu', function (event) {
            event.preventDefault();
            var el = event.target.closest('.vt-row');
            if (el) {
                var index = parseInt(el.getAttribute('data-index'), 10);
                var row = self.rows[index];
                if (row && !self.selected.has(row.seq)) {
                    self.selected.clear();
                    self.selected.add(row.seq);
                    self.lastIndex = index;
                    self.paint();
                    self.notifySelection();
                }
            }
            showContextMenu(self, event.clientX, event.clientY);
        });
        this.root.addEventListener('keydown', function (event) {
            self.handleKey(event);
        });
    }

    VirtualTable.prototype.setRows = function (rows, total) {
        this.rows = rows || [];
        this.total = typeof total === 'number' ? total : this.rows.length;
        this.selected.clear();
        this.lastIndex = -1;
        this.spacer.style.height = (this.rows.length * ROW_H) + 'px';
        this.body.classList.toggle('has-rows', this.rows.length > 0);
        this.body.scrollTop = 0;
        this.rowsEl.style.transform = 'translateY(0)';
        this.paint();
        this.notifySelection();
    };

    VirtualTable.prototype.setEmptyMessage = function (message) {
        this.empty.textContent = message || this.emptyDefault;
    };

    VirtualTable.prototype.paint = function () {
        var viewport = this.body.clientHeight || 300;
        var start = Math.max(0, Math.floor(this.body.scrollTop / ROW_H) - 4);
        var count = Math.ceil(viewport / ROW_H) + 9;
        var end = Math.min(this.rows.length, start + count);
        var html = [];
        for (var i = start; i < end; i++) {
            var row = this.rows[i];
            var cls = this.selected.has(row.seq) ? ' selected' : '';
            html.push('<div class="vt-row' + cls + '" data-index="' + i + '">'
                + '<div class="vt-cell col-seq">' + row.seq + '</div>'
                + '<div class="vt-cell col-time">' + highlightText(row.time) + '</div>'
                + '<div class="vt-cell col-sql">' + highlightText(row.sql) + '</div>'
                + '</div>');
        }
        this.rowsEl.style.transform = 'translateY(' + (start * ROW_H) + 'px)';
        this.rowsEl.innerHTML = html.join('');
    };

    VirtualTable.prototype.handleClick = function (event) {
        var el = event.target.closest('.vt-row');
        if (!el) {
            return;
        }
        var index = parseInt(el.getAttribute('data-index'), 10);
        var row = this.rows[index];
        if (!row) {
            return;
        }
        if (event.shiftKey && this.lastIndex >= 0) {
            if (!event.ctrlKey && !event.metaKey) {
                this.selected.clear();
            }
            var from = Math.min(this.lastIndex, index);
            var to = Math.max(this.lastIndex, index);
            for (var i = from; i <= to; i++) {
                this.selected.add(this.rows[i].seq);
            }
        } else if (event.ctrlKey || event.metaKey) {
            if (this.selected.has(row.seq)) {
                this.selected.delete(row.seq);
            } else {
                this.selected.add(row.seq);
            }
        } else {
            this.selected.clear();
            this.selected.add(row.seq);
        }
        this.lastIndex = index;
        this.paint();
        this.notifySelection();
        this.root.focus({ preventScroll: true });
    };

    VirtualTable.prototype.handleKey = function (event) {
        if (event.key === 'Escape') {
            this.selected.clear();
            this.paint();
            this.notifySelection();
            return;
        }
        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'a') {
            event.preventDefault();
            this.selected.clear();
            this.rows.forEach(function (row) {
                this.selected.add(row.seq);
            }, this);
            this.paint();
            this.notifySelection();
            return;
        }
        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'c') {
            event.preventDefault();
            copyRows(this, true);
        }
    };

    VirtualTable.prototype.notifySelection = function () {
        if (typeof this.onSelectionChange === 'function') {
            this.onSelectionChange(this);
        }
    };

    VirtualTable.prototype.selectedRows = function () {
        return this.rows.filter(function (row) {
            return this.selected.has(row.seq);
        }, this);
    };

    var redoTable = new VirtualTable($('redoTable'), 'redo');
    var undoTable = new VirtualTable($('undoTable'), 'undo');

    redoTable.onSelectionChange = updateCounts;
    undoTable.onSelectionChange = updateCounts;

    function updateCounts() {
        $('redoCount').textContent = countText(redoTable);
        $('undoCount').textContent = countText(undoTable);
        $('btnRedoCopyAll').disabled = redoTable.rows.length === 0;
        $('btnRedoSaveAll').disabled = redoTable.rows.length === 0;
        $('btnUndoCopyAll').disabled = undoTable.rows.length === 0;
        $('btnUndoSaveAll').disabled = undoTable.rows.length === 0;
    }

    function countText(table) {
        var text = table.rows.length < table.total
            ? '匹配 ' + table.rows.length + ' / 共 ' + table.total + ' 条'
            : '共 ' + table.total + ' 条';
        if (table.selected.size > 0) {
            text += ' · 已选 ' + table.selected.size;
        }
        return text;
    }

    function resetSearch() {
        clearTimeout(state.searchTimer);
        $('resultSearch').value = '';
        $('resultSearch').disabled = true;
        $('btnClearSearch').hidden = true;
        state.searchTokens = [];
        state.searchRegex = null;
        $('searchHint').textContent = state.analyzing
            ? '正在分析，完成后可搜索结果…'
            : '暂无可搜索的结果';
    }

    function resetResults() {
        state.entries = [];
        state.redoRows = [];
        state.undoRows = [];
        resetSearch();
        redoTable.setRows([]);
        undoTable.setRows([]);
        updateCounts();
    }

    function rowMatches(row, tokens) {
        var haystack = [
            row.time,
            row.schema && row.table ? row.schema + '.' + row.table : '',
            row.sql
        ].map(function (value) {
            return String(value == null ? '' : value).toLowerCase();
        }).join('\n');
        for (var i = 0; i < tokens.length; i++) {
            if (haystack.indexOf(tokens[i]) < 0) {
                return false;
            }
        }
        return true;
    }

    function refreshResultSearch() {
        var query = ($('resultSearch').value || '').trim();
        var tokens = query ? query.toLowerCase().split(/\s+/).filter(Boolean) : [];
        tokens.sort(function (a, b) { return b.length - a.length; });
        state.searchTokens = tokens;
        state.searchRegex = tokens.length
            ? new RegExp(tokens.map(escapeRegExp).join('|'), 'gi')
            : null;

        var redoRows = tokens.length ? state.redoRows.filter(function (row) {
            return rowMatches(row, tokens);
        }) : state.redoRows;
        var undoRows = tokens.length ? state.undoRows.filter(function (row) {
            return rowMatches(row, tokens);
        }) : state.undoRows;

        redoTable.setRows(redoRows, state.redoRows.length);
        undoTable.setRows(undoRows, state.undoRows.length);

        redoTable.setEmptyMessage(tokens.length && !redoRows.length && state.redoRows.length
            ? '没有匹配“' + query + '”的 redo 记录，按 Esc 清除搜索条件。'
            : null);
        undoTable.setEmptyMessage(tokens.length && !undoRows.length && state.undoRows.length
            ? '没有匹配“' + query + '”的 undo 记录，按 Esc 清除搜索条件。'
            : null);
        $('btnClearSearch').hidden = !query;

        if (!state.redoRows.length) {
            $('searchHint').textContent = state.analyzing
                ? '正在分析，完成后可搜索结果…'
                : '暂无可搜索的结果';
        } else if (tokens.length) {
            $('searchHint').innerHTML = 'redo 匹配 <strong>' + redoRows.length + '</strong> 条 · undo 匹配 <strong>'
                + undoRows.length + '</strong> 条 · 按 Esc 清除搜索';
        } else {
            $('searchHint').textContent = '搜索表名、SQL 内容或时间，空格分隔多个关键字';
        }
        updateCounts();
    }

    function renderResults() {
        var entries = state.entries || [];
        state.redoRows = entries.map(function (entry) {
            return {
                seq: entry.seq,
                timestamp: entry.timestamp,
                time: formatTimestamp(entry.timestamp),
                sql: entry.redo,
                type: entry.type,
                schema: entry.schema,
                table: entry.table
            };
        });
        var undoRows = [];
        for (var i = entries.length - 1; i >= 0; i--) {
            var entry = entries[i];
            undoRows.push({
                seq: entry.seq,
                timestamp: entry.timestamp,
                time: formatTimestamp(entry.timestamp),
                sql: entry.undo,
                type: entry.type,
                schema: entry.schema,
                table: entry.table
            });
        }
        state.undoRows = undoRows;
        $('resultSearch').disabled = entries.length === 0;
        refreshResultSearch();
        applyDisplayFilter();
    }

    function applyDisplayFilter() {
        var display = $('display').value;
        $('redoPanel').hidden = display === 'undo';
        $('undoPanel').hidden = display === 'redo';
        requestAnimationFrame(function () {
            if (!$('redoPanel').hidden) {
                redoTable.paint();
            }
            if (!$('undoPanel').hidden) {
                undoTable.paint();
            }
        });
    }

    function rowsToText(rows) {
        return rows.map(function (row) {
            return row.sql;
        }).join('\n');
    }

    async function copyText(text) {
        if (!text) {
            toast('没有可复制的内容', 'warn');
            return false;
        }
        try {
            if (navigator.clipboard && window.isSecureContext) {
                await navigator.clipboard.writeText(text);
                return true;
            }
        } catch (ignored) {
            // fall back to execCommand
        }
        var textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.setAttribute('readonly', 'readonly');
        textarea.style.position = 'fixed';
        textarea.style.left = '-9999px';
        textarea.style.top = '0';
        document.body.appendChild(textarea);
        textarea.select();
        var ok = false;
        try {
            ok = document.execCommand('copy');
        } catch (e) {
            ok = false;
        }
        document.body.removeChild(textarea);
        return ok;
    }

    async function copyRows(table, selectedOnly) {
        var rows = selectedOnly ? table.selectedRows() : table.rows;
        if (selectedOnly && !rows.length) {
            toast('请先选择要复制的记录', 'warn');
            return;
        }
        if (!rows.length) {
            toast('没有可复制的记录', 'warn');
            return;
        }
        var ok = await copyText(rowsToText(rows));
        if (ok) {
            toast('已复制 ' + rows.length + ' 条 SQL 到剪贴板', 'ok');
        } else {
            toast('复制失败，请手动选择内容复制', 'error');
        }
    }

    function triggerDownload(blob, fileName) {
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        a.download = fileName;
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(function () {
            URL.revokeObjectURL(url);
        }, 4000);
    }

    function filenameFromHeader(header, fallback) {
        if (!header) {
            return fallback;
        }
        var match = /filename="?([^";]+)"?/i.exec(header);
        if (!match) {
            return fallback;
        }
        try {
            return decodeURIComponent(match[1]);
        } catch (e) {
            return match[1];
        }
    }

    async function saveRows(type, selectedOnly) {
        if (!state.job || !TERMINAL[state.job.status]) {
            toast('请先完成一次分析再保存结果', 'warn');
            return;
        }
        var table = type === 'redo' ? redoTable : undoTable;
        var seqs = null;
        if (selectedOnly) {
            seqs = table.selectedRows().map(function (row) { return row.seq; });
            if (!seqs.length) {
                toast('请先选择要保存的记录', 'warn');
                return;
            }
        } else if (table.rows.length < table.total) {
            seqs = table.rows.map(function (row) { return row.seq; });
        }
        try {
            toast('正在生成 ' + (type === 'redo' ? 'Redo SQL' : 'Undo SQL') + ' 文件…', 'info', 1800);
            var response = await fetch('/api/analysis/' + encodeURIComponent(state.job.id) + '/export', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ type: type, seqs: seqs })
            });
            if (!response.ok) {
                var message = '保存失败（HTTP ' + response.status + '）';
                try {
                    var data = await response.json();
                    if (data && data.message) {
                        message = data.message;
                    }
                } catch (ignored) {
                    // ignore
                }
                throw new Error(message);
            }
            var blob = await response.blob();
            var fileName = filenameFromHeader(response.headers.get('content-disposition'),
                type + '-sql-' + Date.now() + '.sql');
            triggerDownload(blob, fileName);
            toast('已保存 ' + fileName + '（字符集 ' + (state.job.charset || 'utf8') + '）', 'ok', 5000);
        } catch (e) {
            toast(e.message, 'error', 7000);
        }
    }

    function showContextMenu(table, x, y) {
        state.menuTable = table;
        var menu = $('contextMenu');
        var hasSelection = table.selected.size > 0;
        var buttons = menu.querySelectorAll('button');
        for (var i = 0; i < buttons.length; i++) {
            var action = buttons[i].getAttribute('data-action');
            buttons[i].disabled = (action === 'copy-selected' || action === 'save-selected') && !hasSelection;
        }
        menu.hidden = false;
        var rect = menu.getBoundingClientRect();
        var left = Math.min(x, window.innerWidth - rect.width - 8);
        var top = Math.min(y, window.innerHeight - rect.height - 8);
        menu.style.left = Math.max(6, left) + 'px';
        menu.style.top = Math.max(6, top) + 'px';
    }

    function hideContextMenu() {
        $('contextMenu').hidden = true;
    }

    function openDetail(row) {
        state.detailRow = row;
        $('detailComment').textContent = '#' + formatDetail(row.timestamp);
        $('detailSql').textContent = row.sql;
        $('detailMeta').textContent = '序号 ' + row.seq + ' · ' + (row.type || '') + ' · '
            + row.schema + '.' + row.table;
        $('detailModal').hidden = false;
        $('btnCopyDetail').focus();
    }

    function closeModal(id) {
        $(id).hidden = true;
    }

    function openDownloadModal() {
        if (!state.connected) {
            toast('请先获取数据字典', 'warn');
            return;
        }
        $('downloadModal').hidden = false;
        $('downloadNote').textContent = '正在读取服务器 binlog 列表…';
        $('downloadMeta').textContent = '';
        var select = $('downloadFile');
        select.innerHTML = '<option value="">加载中…</option>';
        $('btnDownload').disabled = true;
        loadDownloadFiles();
    }

    async function loadDownloadFiles() {
        try {
            var files = await api('/api/binlogs');
            state.binlogFiles = files || [];
            var select = $('downloadFile');
            if (!state.binlogFiles.length) {
                select.innerHTML = '<option value="">服务器没有可用的 binlog</option>';
                $('downloadNote').textContent = '未找到可下载的 binlog，请确认已开启 log_bin 并有相应权限。';
                return;
            }
            select.innerHTML = state.binlogFiles.map(function (file) {
                return '<option value="' + escapeHtml(file.name) + '">' + escapeHtml(file.name)
                    + ' — ' + formatBytes(file.size) + (file.encrypted ? '（已加密）' : '') + '</option>';
            }).join('');
            select.value = state.binlogFiles[state.binlogFiles.length - 1].name;
            $('btnDownload').disabled = false;
            updateDownloadNote();
        } catch (e) {
            $('downloadFile').innerHTML = '<option value="">读取失败</option>';
            $('downloadNote').textContent = e.message;
            toast('读取 binlog 列表失败：' + e.message, 'error', 7000);
        }
    }

    function updateDownloadNote() {
        var name = $('downloadFile').value;
        var file = null;
        for (var i = 0; i < state.binlogFiles.length; i++) {
            if (state.binlogFiles[i].name === name) {
                file = state.binlogFiles[i];
                break;
            }
        }
        if (!file) {
            $('downloadNote').textContent = '';
            $('downloadMeta').textContent = '';
            return;
        }
        $('downloadMeta').textContent = formatBytes(file.size);
        if (file.encrypted) {
            $('downloadNote').textContent = '注意：该日志已加密，mysqlbinlog 在没有密钥环的情况下无法下载。';
        } else {
            $('downloadNote').textContent = '文件大小 ' + formatBytes(file.size)
                + '，下载过程由服务器上的 mysqlbinlog 完成，大文件可能需要等待。';
        }
    }

    async function downloadBinlog() {
        var file = $('downloadFile').value;
        if (!file) {
            toast('请选择要下载的日志', 'warn');
            return;
        }
        var button = $('btnDownload');
        setBusy(button, true, '正在准备…');
        $('downloadNote').textContent = '正在通过 mysqlbinlog 从服务器读取日志，请稍候…';
        try {
            var ticket = await api('/api/binlogs/download', { method: 'POST', body: { file: file } });
            var a = document.createElement('a');
            a.href = ticket.url;
            a.style.display = 'none';
            document.body.appendChild(a);
            a.click();
            a.remove();
            $('downloadNote').textContent = '已准备 ' + ticket.fileName + '（' + formatBytes(ticket.size) + '），浏览器开始下载…';
            toast('开始下载 ' + ticket.fileName, 'ok', 5000);
            setTimeout(function () {
                closeModal('downloadModal');
            }, 900);
        } catch (e) {
            $('downloadNote').textContent = e.message;
            toast(e.message, 'error', 8000);
        } finally {
            setBusy(button, false);
        }
    }

    function uploadFile(file) {
        if (!file || state.uploading) {
            return;
        }
        if (file.size === 0) {
            toast('所选文件为空', 'warn');
            return;
        }
        var xhr = new XMLHttpRequest();
        var formData = new FormData();
        formData.append('file', file);
        state.uploading = true;
        updateAnalyzeButton();
        $('uploadProgress').hidden = false;
        $('uploadBar').style.width = '0%';
        $('uploadPercent').textContent = '0%';

        xhr.upload.onprogress = function (event) {
            if (event.lengthComputable) {
                var percent = Math.round(event.loaded / event.total * 100);
                $('uploadBar').style.width = percent + '%';
                $('uploadPercent').textContent = percent + '%';
            }
        };
        xhr.onload = function () {
            state.uploading = false;
            updateAnalyzeButton();
            if (xhr.status >= 200 && xhr.status < 300) {
                var info;
                try {
                    info = JSON.parse(xhr.responseText);
                } catch (e) {
                    info = null;
                }
                $('uploadPercent').textContent = '100%';
                toast('离线 binlog 上传成功', 'ok');
                loadUploads(info ? info.id : null);
                setTimeout(function () {
                    $('uploadProgress').hidden = true;
                }, 1200);
            } else {
                var message = '上传失败（HTTP ' + xhr.status + '）';
                try {
                    var data = JSON.parse(xhr.responseText);
                    if (data && data.message) {
                        message = data.message;
                    }
                } catch (ignored) {
                    // ignore
                }
                $('uploadProgress').hidden = true;
                toast(message, 'error', 8000);
            }
            $('offlineFile').value = '';
        };
        xhr.onerror = function () {
            state.uploading = false;
            updateAnalyzeButton();
            $('uploadProgress').hidden = true;
            toast('上传失败，请检查网络连接或文件大小限制', 'error', 7000);
            $('offlineFile').value = '';
        };
        xhr.open('POST', '/api/uploads');
        xhr.send(formData);
    }

    function bindEvents() {
        $('connectionForm').addEventListener('submit', connect);
        $('btnDisconnect').addEventListener('click', disconnect);
        $('btnTogglePassword').addEventListener('click', function () {
            var input = $('password');
            var visible = input.type === 'password';
            input.type = visible ? 'text' : 'password';
            this.textContent = visible ? '隐藏' : '显示';
            this.setAttribute('aria-pressed', visible ? 'true' : 'false');
            var label = visible ? '隐藏密码' : '显示密码';
            this.setAttribute('aria-label', label);
            this.setAttribute('title', label);
        });

        $('schema').addEventListener('change', function () {
            localStorage.setItem('binlog.schema', this.value);
            if (this.value) {
                loadTables(this.value);
            } else {
                $('table').innerHTML = '<option value="">全部表</option>';
                $('table').disabled = true;
                updateAnalyzeButton();
            }
            updateAnalyzeButton();
        });
        $('table').addEventListener('change', function () {
            localStorage.setItem('binlog.table.' + $('schema').value, this.value);
        });

        var modeButtons = document.querySelectorAll('.mode-btn');
        for (var i = 0; i < modeButtons.length; i++) {
            modeButtons[i].addEventListener('click', function () {
                setMode(this.getAttribute('data-mode'));
            });
        }

        $('btnAnalyze').addEventListener('click', startAnalysis);
        $('btnCancel').addEventListener('click', cancelAnalysis);
        $('display').addEventListener('change', applyDisplayFilter);

        $('resultSearch').addEventListener('input', function () {
            $('btnClearSearch').hidden = !this.value;
            clearTimeout(state.searchTimer);
            state.searchTimer = setTimeout(refreshResultSearch, 120);
        });
        $('resultSearch').addEventListener('keydown', function (event) {
            if (event.key === 'Escape') {
                event.stopPropagation();
                if (this.value) {
                    this.value = '';
                    $('btnClearSearch').hidden = true;
                    clearTimeout(state.searchTimer);
                    refreshResultSearch();
                } else {
                    this.blur();
                }
            }
        });
        $('btnClearSearch').addEventListener('click', function () {
            var input = $('resultSearch');
            input.value = '';
            $('btnClearSearch').hidden = true;
            clearTimeout(state.searchTimer);
            refreshResultSearch();
            input.focus();
        });

        $('btnRedoCopyAll').addEventListener('click', function () { copyRows(redoTable, false); });
        $('btnRedoSaveAll').addEventListener('click', function () { saveRows('redo', false); });
        $('btnUndoCopyAll').addEventListener('click', function () { copyRows(undoTable, false); });
        $('btnUndoSaveAll').addEventListener('click', function () { saveRows('undo', false); });
        $('btnRedoMax').addEventListener('click', function () {
            $('redoPanel').classList.toggle('tall');
            requestAnimationFrame(function () { redoTable.paint(); });
        });
        $('btnUndoMax').addEventListener('click', function () {
            $('undoPanel').classList.toggle('tall');
            requestAnimationFrame(function () { undoTable.paint(); });
        });

        $('contextMenu').addEventListener('click', function (event) {
            var button = event.target.closest('button[data-action]');
            if (!button || button.disabled) {
                return;
            }
            var table = state.menuTable;
            hideContextMenu();
            if (!table) {
                return;
            }
            var action = button.getAttribute('data-action');
            if (action === 'copy-selected') {
                copyRows(table, true);
            } else if (action === 'copy-all') {
                copyRows(table, false);
            } else if (action === 'save-selected') {
                saveRows(table.kind, true);
            } else if (action === 'save-all') {
                saveRows(table.kind, false);
            }
        });

        document.addEventListener('click', function (event) {
            if (!event.target.closest('#contextMenu')) {
                hideContextMenu();
            }
        });
        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') {
                hideContextMenu();
                if (!$('detailModal').hidden) {
                    closeModal('detailModal');
                }
                if (!$('downloadModal').hidden) {
                    closeModal('downloadModal');
                }
            }
        });
        window.addEventListener('resize', function () {
            redoTable.paint();
            undoTable.paint();
            hideContextMenu();
        });
        window.addEventListener('blur', hideContextMenu);

        var closers = document.querySelectorAll('[data-close]');
        for (var j = 0; j < closers.length; j++) {
            closers[j].addEventListener('click', function () {
                closeModal(this.getAttribute('data-close'));
            });
        }
        var masks = document.querySelectorAll('.modal-mask');
        for (var k = 0; k < masks.length; k++) {
            masks[k].addEventListener('mousedown', function (event) {
                if (event.target === this) {
                    this.hidden = true;
                }
            });
        }
        $('btnCopyDetail').addEventListener('click', async function () {
            if (!state.detailRow) {
                return;
            }
            var ok = await copyText(state.detailRow.sql);
            toast(ok ? 'SQL 已复制到剪贴板' : '复制失败，请手动选择复制', ok ? 'ok' : 'error');
        });

        $('btnOpenDownload').addEventListener('click', openDownloadModal);
        $('downloadFile').addEventListener('change', updateDownloadNote);
        $('btnDownload').addEventListener('click', downloadBinlog);

        var dropZone = $('dropZone');
        var fileInput = $('offlineFile');
        dropZone.addEventListener('click', function (event) {
            if (event.target.closest('#btnPickFile') || event.target === dropZone
                || !event.target.closest('button')) {
                fileInput.click();
            }
        });
        $('btnPickFile').addEventListener('click', function (event) {
            event.stopPropagation();
            fileInput.click();
        });
        fileInput.addEventListener('change', function () {
            if (this.files && this.files[0]) {
                uploadFile(this.files[0]);
            }
        });
        ['dragenter', 'dragover'].forEach(function (name) {
            dropZone.addEventListener(name, function (event) {
                event.preventDefault();
                event.stopPropagation();
                dropZone.classList.add('dragover');
            });
        });
        ['dragleave', 'drop'].forEach(function (name) {
            dropZone.addEventListener(name, function (event) {
                event.preventDefault();
                event.stopPropagation();
                dropZone.classList.remove('dragover');
            });
        });
        dropZone.addEventListener('drop', function (event) {
            if (event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0]) {
                uploadFile(event.dataTransfer.files[0]);
            }
        });

        $('uploadSelect').addEventListener('change', updateAnalyzeButton);

        $('keyword').addEventListener('keydown', function (event) {
            if (event.key === 'Enter') {
                event.preventDefault();
                startAnalysis();
            }
        });
    }

    async function init() {
        bindEvents();
        $('host').value = localStorage.getItem('binlog.host') || 'localhost';
        $('port').value = localStorage.getItem('binlog.port') || '3306';
        $('user').value = localStorage.getItem('binlog.user') || 'root';
        updateCounts();
        loadUploads();
        try {
            var info = await api('/api/dictionary/status');
            if (info && info.connected) {
                showConnected(info);
                await Promise.all([loadBinlogs(), loadUploads()]);
            }
        } catch (ignored) {
            // the service might still be starting
        }
    }

    init();
})();
