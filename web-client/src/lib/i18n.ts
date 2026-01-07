import { create } from 'zustand';
import { persist } from 'zustand/middleware';

type Language = 'en' | 'zh';

type Translations = {
    common: {
        loading: string;
        error: string;
        send: string;
        cancel: string;
        save: string;
        delete: string;
        confirm: string;
        copied: string;
    };
    sidebar: {
        dashboard: string;
        library: string;
        graph: string;
        settings: string;
        assistants: string;
        manage: string;
        newChat: string;
        signOut: string;
        systemOnline: string;
        menu: string;
        unassigned: string;
    };
    chat: {
        placeholder: string;
        generating: string;
        newChat: string;
        modelSelector: string;
        rag: string;
        search: string;
        thinking: string;
        ragTooltip: string;
        searchTooltip: string;
        thinkingTooltip: string;
        history: string;
        clearHistory: string;
        confirmClear: string;
        aiGenerated: string;
        processing: string;
        memorized: string;
        failed: string;
        deleteMessageConfirm: string;
        deleteSession: string;
        confirmDelete: string;
    };
    settings: {
        title: string;
        language: string;
        theme: string;
        about: string;
        backup: {
            title: string;
            description: string;
            localBackup: string;
            export: string;
            import: string;
            exportSuccess: string;
            importSuccess: string;
            importError: string;
            webdav: {
                title: string;
                server: string;
                username: string;
                password: string;
                autoBackup: string;
                save: string;
                saving: string;
            };
        };
        usage: {
            title: string;
            subtitle: string;
            totalToken: string;
            prompt: string;
            completion: string;
            rag: string;
            cost: string;
            reset: string;
            resetConfirm: string;
            stats: {
                title: string;
                input: string;
                output: string;
                total: string;
            };
        };
        rag: { // Legacy or shared
            title: string;
        };
        ragBasic: {
            title: string;
            chunking: {
                title: string;
                docSize: string;
                overlap: string;
                window: string;
                threshold: string;
            };
            observability: {
                title: string;
                progress: string;
                details: string;
                metrics: string;
                cleanup: string;
            };
        };
        ragRetrieval: {
            title: string;
            rerank: {
                title: string;
                enable: string;
                topK: string;
                finalK: string;
            };
            queryRewrite: {
                title: string;
                enable: string;
                strategy: string;
                count: string;
            };
            hybrid: {
                title: string;
                enable: string;
                alpha: string;
                bm25Boost: string;
            };
        };
        ragKg: {
            title: string;
            description: string;
            strategy: string;
            optimization: string;
            prompt: string;
            incremental: string;
            local: string;
        };
        models: {
            title: string;
            addProvider: string;
            addModel: string;
            editModel: string;
            deleteProvider: string;
            save: string;
            cancel: string;
            capabilities: {
                internet: string;
                vision: string;
                reasoning: string;
            };
        };
    };
    library: {
        title: string;
        subtitle: string;
        upload: string;
        uploading: string;
        newFolder: string;
        empty: string;
        deleteFileConfirm: string;
        deleteFolderConfirm: string;
        name: string;
        type: string;
        size: string;
        actions: string;
    };
    graph: {
        title: string;
        subtitle: string;
        searchPlaceholder: string;
        browser: {
            tabs: {
                library: string;
                sessions: string;
            };
            empty: string;
            allFiles: string;
        };
        filterByType: string;
        legend: {
            title: string;
            tooltip: string;
        };
        nodes: string;
        edges: string;
        physics: string;
        reset: string;
        details: string;
        findRelated: string;
        askChat: string;
        noData: string;
    };
    status: {
        idle: string;
        thinking: string;
        searching: string;
        generating: string;
    };
};

const translations: Record<Language, Translations> = {
    en: {
        common: {
            loading: 'Loading...',
            error: 'An error occurred',
            send: 'Send',
            cancel: 'Cancel',
            save: 'Save',
            delete: 'Delete',
            confirm: 'Confirm',
            copied: 'Copied',
        },
        sidebar: {
            dashboard: 'Dashboard',
            library: 'Library',
            graph: 'Knowledge Graph',
            settings: 'Settings',
            assistants: 'ASSISTANTS',
            manage: 'Manage',
            newChat: 'New Chat',
            signOut: 'Sign Out',
            systemOnline: 'System Online',
            menu: 'MENU',
            unassigned: 'UNASSIGNED',
        },
        chat: {
            placeholder: 'Type a message...',
            generating: 'Generating response...',
            newChat: 'New Chat',
            modelSelector: 'Select Model',
            rag: 'RAG Knowledge',
            search: 'Web Search',
            thinking: 'Deep Thinking',
            ragTooltip: 'Enable retrieval augmented generation',
            searchTooltip: 'Enable web search capabilities',
            thinkingTooltip: 'Enable deep reasoning models',
            history: 'History',
            clearHistory: 'Clear History',
            confirmClear: 'Are you sure you want to clear history?',
            aiGenerated: 'AI GENERATED CONTENT MAY BE INACCURATE',
            processing: 'Processing',
            memorized: 'Memorized',
            failed: 'Error',
            deleteMessageConfirm: 'Delete this message?',
            deleteSession: 'Delete Session',
            confirmDelete: 'Are you sure you want to delete this session?',
        },
        settings: {
            title: 'Settings',
            language: 'Language',
            theme: 'Theme',
            about: 'About',
            backup: {
                title: 'Backup & Restore',
                description: 'Export your data to a JSON file or import from a backup.',
                localBackup: 'Local Backup',
                export: 'Export Backup',
                import: 'Import Backup',
                exportSuccess: 'Backup exported successfully',
                importSuccess: 'Backup imported successfully',
                importError: 'Failed to import backup',
                webdav: {
                    title: 'WebDAV Configuration',
                    server: 'Server URL',
                    username: 'Username',
                    password: 'Password / Token',
                    autoBackup: 'Enable Daily Auto-Backup',
                    save: 'Save Configuration',
                    saving: 'Saving...',
                }
            },
            usage: {
                title: 'Token Usage',
                subtitle: 'Statistics & Cost Estimate',
                totalToken: 'Total Tokens',
                prompt: 'Prompt',
                completion: 'Completion',
                rag: 'RAG System',
                cost: 'Estimated Cost',
                reset: 'Reset Statistics',
                resetConfirm: 'Are you sure you want to reset all token statistics?',
                stats: {
                    title: 'Token Usage',
                    input: 'Input',
                    output: 'Output',
                    total: 'Total',
                },
            },
            ragBasic: {
                title: 'RAG Foundation',
                chunking: {
                    title: 'Chunking & Context',
                    docSize: 'Doc Chunk Size',
                    overlap: 'Chunk Overlap',
                    window: 'Context Window',
                    threshold: 'Summary Threshold',
                },
                observability: {
                    title: 'Observability',
                    progress: 'Show Retrieval Progress',
                    details: 'Show Retrieval Details',
                    metrics: 'Track Metrics',
                    cleanup: 'Auto Cleanup Context',
                },
            },
            ragRetrieval: {
                title: 'Retrieval Strategy',
                rerank: {
                    title: 'Rerank',
                    enable: 'Enable Rerank',
                    topK: 'Initial Top K',
                    finalK: 'Final Top K',
                },
                queryRewrite: {
                    title: 'Query Rewrite',
                    enable: 'Enable Query Rewrite',
                    strategy: 'Strategy',
                    count: 'Variations',
                },
                hybrid: {
                    title: 'Hybrid Search',
                    enable: 'Enable Hybrid Search',
                    alpha: 'Vector Weight (Alpha)',
                    bm25Boost: 'BM25 Boost',
                },
            },
            ragKg: {
                title: 'Knowledge Graph',
                description: 'Extract entities & relationships during ingestion.',
                strategy: 'Extraction Strategy',
                optimization: 'Optimization',
                prompt: 'Extraction Prompt',
                incremental: 'Incremental Hash Check',
                local: 'Local Pre-process',
            },
            rag: {
                title: 'Advanced RAG', // Legacy
            },
            models: {
                title: 'Model Management',
                addProvider: 'Add Provider',
                addModel: 'Add Custom Model',
                editModel: 'Edit Model',
                deleteProvider: 'Delete Provider',
                save: 'Save Model',
                cancel: 'Cancel',
                capabilities: {
                    internet: 'Internet Access',
                    vision: 'Vision Capability',
                    reasoning: 'Reasoning Capability',
                },
            },
        },
        library: {
            title: 'Library',
            subtitle: 'Manage knowledge base',
            upload: 'Upload File',
            uploading: 'Uploading...',
            newFolder: 'New Folder',
            empty: 'This folder is empty',
            deleteFileConfirm: 'Delete this file?',
            deleteFolderConfirm: 'Delete this folder and its contents?',
            name: 'Name',
            type: 'Type',
            size: 'Size',
            actions: 'Actions',
        },
        graph: {
            title: 'Knowledge Graph',
            subtitle: 'Visualize Knowledge Connection',
            searchPlaceholder: 'Search nodes...',
            browser: {
                tabs: {
                    library: 'Library',
                    sessions: 'Sessions',
                },
                empty: 'No items found',
                allFiles: 'All Files',
            },
            legend: {
                title: 'Node Types',
                tooltip: 'Toggle Legend',
            },
            filterByType: 'Filter by Type',
            nodes: 'Nodes',
            edges: 'Edges',
            physics: 'Physics Engine',
            reset: 'Reset View',
            details: 'Node Details',
            findRelated: 'Find Related',
            askChat: 'Ask Chat',
            noData: 'No graph data. Enable KG extraction in RAG settings and add content to Library.',
        },
        status: {
            idle: 'Idle',
            thinking: 'Thinking',
            searching: 'Searching',
            generating: 'Generating',
        },
    },
    zh: {
        common: {
            loading: '加载中...',
            error: '发生错误',
            send: '发送',
            cancel: '取消',
            save: '保存',
            delete: '删除',
            confirm: '确认',
            copied: '已复制',
        },
        sidebar: {
            dashboard: '概览',
            library: '知识库',
            graph: '知识图谱',
            settings: '设置',
            assistants: '智能助手',
            manage: '管理',
            newChat: '新对话',
            signOut: '退出登录',
            systemOnline: '系统在线',
            menu: '菜单',
            unassigned: '未分类',
        },
        chat: {
            placeholder: '输入消息...',
            generating: '正在生成响应...',
            newChat: '新对话',
            modelSelector: '选择模型',
            rag: 'RAG 知识库',
            search: '联网搜索',
            thinking: '深度思考',
            ragTooltip: '启用知识库检索增强',
            searchTooltip: '启用联网搜索能力',
            thinkingTooltip: '启用深度推理模型',
            history: '历史记录',
            clearHistory: '清除历史',
            confirmClear: '确定要清除历史记录吗？',
            aiGenerated: 'AI 生成内容可能不准确，请核实',
            processing: '处理中',
            memorized: '已记忆',
            failed: '错误',
            deleteMessageConfirm: '确定要删除这条消息吗？',
            deleteSession: '删除会话',
            confirmDelete: '确定要删除此会话吗？',
        },
        settings: {
            title: '设置',
            language: '语言',
            theme: '主题',
            about: '关于',
            backup: {
                title: '备份与恢复',
                description: '导出您的数据为 JSON 文件或从备份导入。',
                localBackup: '本地备份',
                export: '导出备份',
                import: '导入备份',
                exportSuccess: '备份导出成功',
                importSuccess: '备份导入成功',
                importError: '导入备份失败',
                webdav: {
                    title: 'WebDAV 配置',
                    server: '服务器地址',
                    username: '用户名',
                    password: '密码 / 令牌',
                    autoBackup: '开启每日自动备份',
                    save: '保存配置',
                    saving: '保存中...',
                }
            },
            usage: {
                title: '用量统计',
                subtitle: 'Token 统计与成本估算',
                totalToken: '总 Token',
                prompt: '提示词',
                completion: '生成',
                rag: 'RAG 系统',
                cost: '预估成本',
                reset: '重置统计',
                resetConfirm: '确定要重置所有统计数据吗？',
                stats: {
                    title: 'Token 用量',
                    input: '输入',
                    output: '输出',
                    total: '总量',
                },
            },
            ragBasic: {
                title: 'RAG 基础配置',
                chunking: {
                    title: '切块与上下文',
                    docSize: '文档切块大小',
                    overlap: '切块重叠',
                    window: '上下文窗口',
                    threshold: '摘要阈值',
                },
                observability: {
                    title: '可观测性',
                    progress: '显示检索进度',
                    details: '显示检索详情',
                    metrics: '记录检索指标',
                    cleanup: '自动清理上下文',
                },
            },
            ragRetrieval: {
                title: 'RAG 检索策略',
                rerank: {
                    title: '重排序 (Rerank)',
                    enable: '启用重排序',
                    topK: '初召回数量',
                    finalK: '精排后返回',
                },
                queryRewrite: {
                    title: '查询重写',
                    enable: '启用查询重写',
                    strategy: '重写策略',
                    count: '变体数量',
                },
                hybrid: {
                    title: '混合检索',
                    enable: '启用混合检索',
                    alpha: '向量权重 (Alpha)',
                    bm25Boost: 'BM25 增益',
                },
            },
            ragKg: {
                title: '知识图谱配置',
                description: '在提取过程中构建实体与关系。',
                strategy: '提取策略',
                optimization: '优化选项',
                prompt: '提取提示词',
                incremental: '增量哈希检查',
                local: '本地预处理',
            },
            rag: { // Keeping generic keys just in case, but structure implies we move them
                title: 'RAG 高级配置', // Legacy
            },
            models: {
                title: '模型管理',
                addProvider: '添加服务商',
                addModel: '添加自定义模型',
                editModel: '编辑模型',
                deleteProvider: '删除服务商',
                save: '保存模型',
                cancel: '取消',
                capabilities: {
                    internet: '联网能力',
                    vision: '视觉能力',
                    reasoning: '推理能力',
                },
            },
        },
        library: {
            title: '知识库',
            subtitle: '管理您的知识资产',
            upload: '上传文件',
            uploading: '上传中...',
            newFolder: '新建文件夹',
            empty: '此文件夹为空',
            deleteFileConfirm: '确定删除此文件？',
            deleteFolderConfirm: '确定删除此文件夹及其内容？',
            name: '名称',
            type: '类型',
            size: '大小',
            actions: '操作',
        },
        graph: {
            title: '知识图谱',
            subtitle: '可视化知识关联',
            searchPlaceholder: '搜索实体...',
            browser: {
                tabs: {
                    library: '知识文库',
                    sessions: '历史会话',
                },
                empty: '暂无数据',
                allFiles: '全部文件',
            },
            filterByType: '按类型筛选',
            legend: {
                title: '节点类型',
                tooltip: '显示/隐藏图例',
            },
            nodes: '节点',
            edges: '关系',
            physics: '物理引擎',
            reset: '重置视图',
            details: '节点详情',
            findRelated: '查找相关',
            askChat: '对此提问',
            noData: '暂无图谱数据，请在 RAG 设置中开启知识图谱提取并在知识库中添加内容。',
        },
        status: {
            idle: '空闲',
            thinking: '思考中',
            searching: '搜索中',
            generating: '生成中',
        },
    },
};

interface I18nStore {
    language: Language;
    t: Translations;
    setLanguage: (lang: Language) => void;
}

export const useI18n = create<I18nStore>()(
    persist(
        (set) => ({
            language: 'zh', // Default to Chinese as per requirement
            t: translations.zh,
            setLanguage: (lang) => set({ language: lang, t: translations[lang] }),
        }),
        {
            name: 'i18n-storage',
        }
    )
);
