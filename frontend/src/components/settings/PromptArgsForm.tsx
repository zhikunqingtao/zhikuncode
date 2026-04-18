import { useState, useCallback } from 'react';
import type { McpPromptArgument } from '@/types';

interface PromptArgsFormProps {
  arguments: McpPromptArgument[];
  onSubmit: (args: Record<string, string>) => void;
  executing?: boolean;
}

/**
 * PromptArgsForm — 动态参数表单组件。
 * 根据 McpPromptArgument 定义动态生成表单字段，包含必填标记和输入验证。
 */
export function PromptArgsForm({ arguments: argDefs, onSubmit, executing }: PromptArgsFormProps) {
  const [values, setValues] = useState<Record<string, string>>(() => {
    const init: Record<string, string> = {};
    for (const arg of argDefs) {
      init[arg.name] = '';
    }
    return init;
  });
  const [errors, setErrors] = useState<Record<string, string>>({});

  const validate = useCallback((): boolean => {
    const newErrors: Record<string, string> = {};
    for (const arg of argDefs) {
      const val = values[arg.name] || '';
      if (arg.required && !val.trim()) {
        newErrors[arg.name] = `${arg.name} is required`;
      } else if (val.length > 10000) {
        newErrors[arg.name] = `${arg.name} exceeds maximum length (10000)`;
      }
    }
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  }, [argDefs, values]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (validate()) {
      onSubmit(values);
    }
  };

  const handleChange = (name: string, value: string) => {
    setValues(prev => ({ ...prev, [name]: value }));
    // Clear error on change
    if (errors[name]) {
      setErrors(prev => {
        const next = { ...prev };
        delete next[name];
        return next;
      });
    }
  };

  if (argDefs.length === 0) {
    return (
      <form onSubmit={handleSubmit}>
        <p className="text-sm text-gray-500 dark:text-gray-400 mb-3">
          This prompt requires no arguments.
        </p>
        <button
          type="submit"
          disabled={executing}
          className="px-4 py-2 text-sm font-medium rounded bg-blue-500 text-white
                     hover:bg-blue-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          {executing ? 'Executing...' : 'Execute Prompt'}
        </button>
      </form>
    );
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-3">
      {argDefs.map((arg) => (
        <div key={arg.name}>
          <label className="block text-xs font-medium text-gray-600 dark:text-gray-400 mb-1">
            {arg.name}
            {arg.required && <span className="text-red-500 ml-0.5">*</span>}
          </label>
          {arg.description && (
            <p className="text-xs text-gray-400 dark:text-gray-500 mb-1">{arg.description}</p>
          )}
          <input
            type="text"
            value={values[arg.name] || ''}
            onChange={(e) => handleChange(arg.name, e.target.value)}
            placeholder={arg.required ? `Required: ${arg.name}` : `Optional: ${arg.name}`}
            className={`w-full p-2 text-sm border rounded dark:bg-gray-700 dark:border-gray-600
              focus:outline-none focus:ring-2 focus:ring-blue-500/50
              ${errors[arg.name] ? 'border-red-400 dark:border-red-500' : 'border-gray-300 dark:border-gray-600'}`}
          />
          {errors[arg.name] && (
            <p className="text-xs text-red-500 mt-1">{errors[arg.name]}</p>
          )}
        </div>
      ))}

      <button
        type="submit"
        disabled={executing}
        className="mt-2 px-4 py-2 text-sm font-medium rounded bg-blue-500 text-white
                   hover:bg-blue-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
      >
        {executing ? 'Executing...' : 'Execute Prompt'}
      </button>
    </form>
  );
}
