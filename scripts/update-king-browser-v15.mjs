#!/usr/bin/env node

import { updateBrowserVerification } from './update-king-browser-v14.mjs';

const chunks = [];
for await (const chunk of process.stdin) chunks.push(chunk);
const raw = Buffer.concat(chunks).toString('utf8').trim();
if (!raw) throw new Error('Expected a direct-browser measurement JSON document on stdin');
updateBrowserVerification(JSON.parse(raw));
