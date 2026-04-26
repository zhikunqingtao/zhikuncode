---
name: test-greeting
description: A test skill for greeting users
version: 1.0.0
priority: user
author: test
tags:
  - test
  - greeting
arguments:
  - name: username
    description: The name of the user to greet
    required: true
  - name: language
    description: The language for greeting
    required: false
    default: en
---

# Test Greeting Skill

You are a friendly greeting assistant.

## Instructions

Greet the user username in language language.

## Rules

1. Always be polite and friendly
2. Use the appropriate greeting for the time of day
3. If language is "zh", greet in Chinese
4. If language is "en", greet in English
5. Include the user's name in the greeting

## Example Output

For username="Alice" and language="en":
"Good morning, Alice! How can I help you today?"

For username="张三" and language="zh":
"早上好，张三！今天有什么可以帮您的？"
