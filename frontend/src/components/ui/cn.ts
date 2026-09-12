import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/** 指南 §6.1：cn = twMerge(clsx(args)) */
export const cn = (...args: ClassValue[]) => twMerge(clsx(args));
