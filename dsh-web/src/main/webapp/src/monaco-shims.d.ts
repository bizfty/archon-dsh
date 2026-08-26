// Monaco 子路径模块无内置类型声明（monaco-editor 主包自带 editor.api 类型，
// 语言贡献/editor.main 子路径缺 d.ts）：此处兜底声明，运行时行为不受影响。
declare module 'monaco-editor/language/typescript/monaco.contribution';
declare module 'monaco-editor/language/json/monaco.contribution';
declare module 'monaco-editor/language/css/monaco.contribution';
declare module 'monaco-editor/language/html/monaco.contribution';
declare module 'monaco-editor/editor/editor.main';
