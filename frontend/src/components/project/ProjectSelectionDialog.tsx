import {
    useCallback,
    useEffect,
    useRef,
    useState,
} from 'react';
import {
    ChevronUp,
    Folder,
    Loader2,
    Trash2,
} from 'lucide-react';
import { Modal } from '@/components/common/Modal';
import {
    projectApiErrorMessage,
    useProjectStore,
} from '@/store/projectStore';

interface DirectoryEntry {
    name: string;
    path: string;
}

interface DirectoryListing {
    roots: string[];
    current: string;
    parent?: string | null;
    directories: DirectoryEntry[];
    nativePickerAvailable?: boolean;
}

function isDirectoryListing(value: unknown): value is DirectoryListing {
    if (typeof value !== 'object' || value === null) return false;
    const listing = value as Partial<DirectoryListing>;
    return Array.isArray(listing.roots)
        && listing.roots.every(root => typeof root === 'string')
        && typeof listing.current === 'string'
        && (listing.parent == null
            || typeof listing.parent === 'string')
        && (listing.nativePickerAvailable === undefined
            || typeof listing.nativePickerAvailable === 'boolean')
        && Array.isArray(listing.directories)
        && listing.directories.every(directory =>
            typeof directory === 'object'
            && directory !== null
            && typeof directory.name === 'string'
            && typeof directory.path === 'string');
}

function nameForPath(path: string): string {
    const parts = path.split(/[\\/]/).filter(Boolean);
    return parts.at(-1) ?? 'Project';
}

export function ProjectSelectionDialog() {
    const {
        projects,
        loading,
        isOpen,
        requesting,
        revokingProjectId,
        error,
        createProject,
        revokeProject,
        confirmSelection,
        cancelSelection,
    } = useProjectStore();
    const [selectedId, setSelectedId] = useState('');
    const [name, setName] = useState('');
    const [workspaceRoot, setWorkspaceRoot] = useState('');
    const [directoryListing, setDirectoryListing] =
        useState<DirectoryListing | null>(null);
    const [directoryLoading, setDirectoryLoading] = useState(false);
    const [nativePickerLoading, setNativePickerLoading] = useState(false);
    const [directoryError, setDirectoryError] = useState<string | null>(null);
    const directoryRequestId = useRef(0);
    const nativePickerInFlight = useRef(false);
    const selectedManually = useRef(false);
    const busy = requesting || revokingProjectId !== null;

    const loadDirectory = useCallback(async (path?: string) => {
        selectedManually.current = false;
        const requestId = ++directoryRequestId.current;
        setDirectoryLoading(true);
        setDirectoryError(null);
        try {
            const params = new URLSearchParams();
            if (path) params.set('path', path);
            const query = params.size > 0 ? `?${params}` : '';
            const response = await fetch(
                `/api/projects/directories${query}`,
            );
            if (!response.ok) {
                throw new Error(
                    await projectApiErrorMessage(response));
            }
            const listing = await response.json() as unknown;
            if (!isDirectoryListing(listing)) {
                throw new Error('服务端返回了无效的目录列表');
            }
            if (requestId !== directoryRequestId.current) return;
            setDirectoryListing(listing);
            setWorkspaceRoot(listing.current);
            setName(nameForPath(listing.current));
        } catch (directoryFailure) {
            if (requestId !== directoryRequestId.current) return;
            setDirectoryError(directoryFailure instanceof Error
                ? directoryFailure.message
                : '加载服务端目录失败');
        } finally {
            if (requestId === directoryRequestId.current) {
                setDirectoryLoading(false);
            }
        }
    }, []);

    useEffect(() => {
        if (!isOpen) {
            // Prevent a native picker response from a closed dialog from
            // replacing the next selection.
            directoryRequestId.current += 1;
            return;
        }
        setSelectedId('');
        setName('');
        setWorkspaceRoot('');
        setDirectoryListing(null);
        setDirectoryError(null);
        void loadDirectory();
    }, [isOpen, loadDirectory]);

    const handleNativePicker = async () => {
        if (busy || directoryLoading || nativePickerInFlight.current) return;
        nativePickerInFlight.current = true;
        const requestId = ++directoryRequestId.current;
        setNativePickerLoading(true);
        try {
            const response = await fetch(
                '/api/projects/directories/pick',
                {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'X-Zhikun-Native-Picker': '1',
                    },
                    body: JSON.stringify({}),
                },
            );
            if (response.status === 204) return;
            if (!response.ok) {
                throw new Error(
                    await projectApiErrorMessage(response));
            }
            const listing = await response.json() as unknown;
            if (!isDirectoryListing(listing)) {
                throw new Error('服务端返回了无效的目录列表');
            }
            if (requestId !== directoryRequestId.current) return;
            selectedManually.current = false;
            setDirectoryError(null);
            setDirectoryListing(listing);
            setWorkspaceRoot(listing.current);
            setName(nameForPath(listing.current));
            const existing = projects.find(
                project => project.workspaceRoot === listing.current);
            setSelectedId(existing?.id ?? '');
        } catch (pickerFailure) {
            if (requestId !== directoryRequestId.current) return;
            setDirectoryError(pickerFailure instanceof Error
                ? pickerFailure.message
                : '选择本机文件夹失败');
        } finally {
            nativePickerInFlight.current = false;
            setNativePickerLoading(false);
        }
    };

    useEffect(() => {
        if (!isOpen || !workspaceRoot) return;
        const existing = projects.find(
            project => project.workspaceRoot === workspaceRoot);
        // A late initial response must not overwrite a radio choice made while
        // it was loading. An explicit directory change resets this flag.
        if (!selectedManually.current) {
            setSelectedId(existing?.id ?? '');
        }
    }, [isOpen, projects, workspaceRoot]);

    const handleCreate = async () => {
        if (!name.trim() || !workspaceRoot.trim() || busy) return;
        try {
            const project = await createProject(
                name.trim(),
                workspaceRoot.trim(),
            );
            selectedManually.current = true;
            setSelectedId(project.id);
        } catch {
            // The store error is rendered below.
        }
    };

    const handleRevoke = async (projectId: string, projectName: string) => {
        if (busy || !window.confirm(
            `确定撤销“${projectName}”的持久自动编辑授权吗？已有 Session 不会被终止。`,
        )) return;
        try {
            await revokeProject(projectId);
            if (selectedId === projectId) {
                selectedManually.current = false;
                setSelectedId('');
            }
        } catch {
            // The store error is rendered below.
        }
    };

    const selected = projects.find(
        project => project.id === selectedId);
    const existingForPath = projects.find(
        project => project.workspaceRoot === workspaceRoot);

    return (
        <Modal
            isOpen={isOpen}
            onClose={cancelSelection}
            title="选择文件夹授权"
        >
            <div className="space-y-4">
                <div className="rounded-lg border border-amber-500/30 bg-amber-500/10 p-3 text-sm text-[var(--text-secondary)]">
                    <p>
                        Project 会把所选服务端目录持久注册为 Session 的默认相对路径根和信任范围；在 DEFAULT 模式下，目录内普通读写无需重复确认。
                    </p>
                    <p className="mt-1 text-xs text-[var(--text-muted)]">
                        目录外的普通文件操作会请求授权，并可按本次运行或会话记住；敏感文件和高风险操作每次都需确认。取消本次选择不会撤销已有授权。
                    </p>
                </div>

                {error && (
                    <div role="alert" className="text-sm text-red-500">
                        {error}
                    </div>
                )}

                <div className="flex justify-end gap-2">
                    <button
                        type="button"
                        onClick={cancelSelection}
                        disabled={busy}
                        className="rounded-lg px-4 py-2 text-sm hover:bg-[var(--bg-hover)] disabled:opacity-50"
                    >
                        取消本次选择
                    </button>
                    <button
                        type="button"
                        onClick={() => selected
                            && confirmSelection(selected)}
                        disabled={!selected || busy}
                        className="rounded-lg bg-blue-600 px-4 py-2 text-sm text-white disabled:opacity-50"
                    >
                        使用所选授权
                    </button>
                </div>

                <section
                    aria-labelledby="authorized-projects-title"
                    className="border-t border-[var(--border)] pt-4"
                >
                    <div
                        id="authorized-projects-title"
                        className="mb-2 text-sm font-medium text-[var(--text-primary)]"
                    >
                        已授权文件夹
                    </div>
                    <div className="max-h-48 overflow-y-auto space-y-2">
                        {loading && projects.length === 0 ? (
                            <div className="flex justify-center py-5">
                                <Loader2 className="w-5 h-5 animate-spin text-[var(--text-muted)]" />
                            </div>
                        ) : projects.length === 0 ? (
                            <div className="text-sm text-[var(--text-muted)] py-2">
                                暂无持久授权，请从服务端目录中选择。
                            </div>
                        ) : projects.map(project => (
                            <div
                                key={project.id}
                                className={`flex items-center gap-2 rounded-lg border p-3 ${
                                    selectedId === project.id
                                        ? 'border-blue-500 bg-blue-500/10'
                                        : 'border-[var(--border)]'
                                }`}
                            >
                                <label className="min-w-0 flex-1 cursor-pointer">
                                    <input
                                        type="radio"
                                        name="project"
                                        value={project.id}
                                        checked={selectedId === project.id}
                                        onChange={() => {
                                            selectedManually.current = true;
                                            setSelectedId(project.id);
                                        }}
                                        disabled={busy}
                                        className="sr-only"
                                    />
                                    <div className="text-sm font-medium text-[var(--text-primary)]">
                                        {project.name}
                                    </div>
                                    <div className="mt-1 truncate text-xs text-[var(--text-muted)]">
                                        {project.workspaceRoot}
                                    </div>
                                </label>
                                <button
                                    type="button"
                                    onClick={() => void handleRevoke(
                                        project.id,
                                        project.name,
                                    )}
                                    disabled={busy}
                                    aria-label={`撤销 ${project.name} 的持久自动编辑授权`}
                                    className="rounded p-2 text-red-500 hover:bg-red-500/10 disabled:opacity-50"
                                >
                                    {revokingProjectId === project.id
                                        ? <Loader2 className="h-4 w-4 animate-spin" />
                                        : <Trash2 className="h-4 w-4" />}
                                </button>
                            </div>
                        ))}
                    </div>
                </section>

                <section
                    aria-label="授权当前所选文件夹"
                    className="border-t border-[var(--border)] pt-4"
                >
                    <label className="block text-xs text-[var(--text-muted)]">
                        授权名称
                        <input
                            value={name}
                            onChange={event => setName(event.target.value)}
                            placeholder="用于识别此持久授权"
                            disabled={busy}
                            className="mt-1 w-full rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] px-3 py-2 text-sm text-[var(--text-primary)]"
                        />
                    </label>

                    <button
                        type="button"
                        onClick={() => void handleCreate()}
                        disabled={busy || directoryLoading
                            || nativePickerLoading
                            || !name.trim() || !workspaceRoot.trim()
                            || Boolean(existingForPath)}
                        className="mt-3 rounded-lg border border-[var(--border)] px-3 py-2 text-sm hover:bg-[var(--bg-hover)] disabled:opacity-50"
                    >
                        {requesting
                            ? '授权中…'
                            : existingForPath
                                ? '此文件夹已授权'
                                : '授权此文件夹'}
                    </button>
                </section>

                {directoryListing?.nativePickerAvailable === true && (
                    <button
                        type="button"
                        onClick={() => void handleNativePicker()}
                        disabled={busy || directoryLoading
                            || nativePickerLoading}
                        className="flex w-full items-center justify-center gap-2 rounded-lg border border-[var(--border)] px-3 py-2 text-sm hover:bg-[var(--bg-hover)] disabled:opacity-50"
                    >
                        {nativePickerLoading ? (
                            <Loader2 className="h-4 w-4 animate-spin" />
                        ) : (
                            <Folder className="h-4 w-4 text-blue-500" />
                        )}
                        {nativePickerLoading
                            ? '正在打开文件夹选择器…'
                            : '选择本机文件夹…'}
                    </button>
                )}

                {directoryError && (
                    <div role="alert" className="text-sm text-red-500">
                        {directoryError}
                    </div>
                )}

                <section
                    aria-labelledby="server-directory-title"
                    className="border-t border-[var(--border)] pt-4"
                >
                    <div
                        id="server-directory-title"
                        className="text-sm font-medium text-[var(--text-primary)]"
                    >
                        浏览服务端目录
                    </div>
                    <p className="mt-1 text-xs text-[var(--text-muted)]">
                        这里浏览的是运行 ZhikunCode 的服务器目录，不会上传浏览器本地文件。
                    </p>

                    {directoryListing?.roots.length ? (
                        <div className="mt-3 flex flex-wrap gap-2">
                            {directoryListing.roots.map(root => (
                                <button
                                    type="button"
                                    key={root}
                                    onClick={() => void loadDirectory(root)}
                                    disabled={busy || directoryLoading
                                        || nativePickerLoading}
                                    className="rounded border border-[var(--border)] px-2 py-1 text-xs hover:bg-[var(--bg-hover)] disabled:opacity-50"
                                >
                                    {root}
                                </button>
                            ))}
                        </div>
                    ) : null}

                    <div className="mt-3 rounded-lg border border-[var(--border)]">
                        <div className="flex items-center gap-2 border-b border-[var(--border)] px-3 py-2">
                            <Folder className="h-4 w-4 shrink-0 text-blue-500" />
                            <span className="min-w-0 flex-1 truncate font-mono text-xs">
                                {directoryListing?.current
                                    || '正在加载可授权目录…'}
                            </span>
                            {directoryListing?.parent && (
                                <button
                                    type="button"
                                    onClick={() => void loadDirectory(
                                        directoryListing.parent ?? undefined,
                                    )}
                                    disabled={busy || directoryLoading
                                        || nativePickerLoading}
                                    aria-label="打开上一级目录"
                                    className="rounded p-1 hover:bg-[var(--bg-hover)] disabled:opacity-50"
                                >
                                    <ChevronUp className="h-4 w-4" />
                                </button>
                            )}
                        </div>

                        <div className="max-h-44 overflow-y-auto p-1">
                            {directoryLoading ? (
                                <div className="flex justify-center py-5">
                                    <Loader2 className="h-5 w-5 animate-spin text-[var(--text-muted)]" />
                                </div>
                            ) : directoryListing?.directories.length ? (
                                directoryListing.directories.map(directory => (
                                    <button
                                        type="button"
                                        key={directory.path}
                                        onClick={() => void loadDirectory(
                                            directory.path,
                                        )}
                                        disabled={busy || nativePickerLoading}
                                        aria-label={`打开目录 ${directory.name}`}
                                        className="flex w-full items-center gap-2 rounded px-2 py-2 text-left text-sm hover:bg-[var(--bg-hover)] disabled:opacity-50"
                                    >
                                        <Folder className="h-4 w-4 shrink-0 text-blue-500" />
                                        <span className="truncate">
                                            {directory.name}
                                        </span>
                                    </button>
                                ))
                            ) : (
                                <div className="px-2 py-4 text-center text-xs text-[var(--text-muted)]">
                                    当前目录没有可浏览的子目录
                                </div>
                            )}
                        </div>
                    </div>

                    <details className="mt-3 text-sm">
                        <summary className="cursor-pointer text-[var(--text-muted)]">
                            高级：手动输入服务端绝对路径
                        </summary>
                        <div className="mt-2 space-y-2">
                            <label className="block text-xs text-[var(--text-muted)]">
                                服务端绝对路径
                                <input
                                    value={workspaceRoot}
                                    onChange={event => {
                                        const nextPath = event.target.value;
                                        // Manual input is a newer explicit
                                        // choice than any pending browse call.
                                        directoryRequestId.current += 1;
                                        setDirectoryLoading(false);
                                        selectedManually.current = false;
                                        setWorkspaceRoot(nextPath);
                                        setName(nameForPath(nextPath));
                                    }}
                                    placeholder="例如 /srv/projects/demo"
                                    disabled={busy || nativePickerLoading}
                                    className="mt-1 w-full rounded-lg border border-[var(--border)] bg-[var(--bg-secondary)] px-3 py-2 text-sm text-[var(--text-primary)]"
                                />
                            </label>
                        </div>
                    </details>
                </section>
            </div>
        </Modal>
    );
}
