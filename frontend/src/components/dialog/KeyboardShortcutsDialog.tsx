import { Button, Dialog, Kbd } from '@/components/ui';

/** 展示当前输入框与全局命令面板已支持的快捷键。 */
export function KeyboardShortcutsDialog({ onClose }: { onClose: () => void }) {
    const isMac = navigator.platform.includes('Mac');
    const shortcuts = [
        { keys: ['Enter'], description: '发送消息' },
        { keys: ['Shift', 'Enter'], description: '换行' },
        { keys: ['/'], description: '空输入框打开命令列表' },
        { keys: [isMac ? '⌘' : 'Ctrl', 'K'], description: '打开全局命令面板' },
        { keys: ['Esc'], description: '关闭弹窗；输入框中关闭命令列表或清空草稿' },
        { keys: ['Ctrl', 'C'], description: '输入框内中断生成（未选中文字时）' },
    ];

    return (
        <Dialog open title="快捷键帮助" onClose={onClose} className="max-w-lg border border-hairline">
            <div className="p-5">
                <dl className="space-y-4 text-sm">
                    {shortcuts.map(({ keys, description }) => (
                        <div key={description} className="flex items-center justify-between gap-4">
                            <dt className="text-t2">{description}</dt>
                            <dd className="flex shrink-0 items-center gap-1">
                                {keys.map(key => <Kbd key={key}>{key}</Kbd>)}
                            </dd>
                        </div>
                    ))}
                </dl>
                <div className="mt-5 flex justify-end">
                    <Button variant="primary" onClick={onClose}>完成</Button>
                </div>
            </div>
        </Dialog>
    );
}
