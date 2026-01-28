import { Skill } from '../../types/skills';
import { useSettingsStore } from '../../store/settings-store';
import { coreSkills } from './definitions';
import { ShowDebugToastSkill } from './core/debug';
import { TaskManagementSkill } from './core/task';
import { RenderChartSkill, RenderMermaidSkill } from './core/rendering'; // ✅ New Import

class SkillRegistry {
    private skills: Map<string, Skill> = new Map();
    private static instance: SkillRegistry;

    private constructor() {
        // Register Core Skills
        coreSkills.forEach(skill => this.register(skill));

        // Register Debug Skill
        this.register(ShowDebugToastSkill);

        // Register Task Management Skill
        this.register(TaskManagementSkill);

        // Register Rendering Skills
        this.register(RenderChartSkill);
        this.register(RenderMermaidSkill);

        // Register Meta Skill
        const { ToolManagerSkill } = require('./definitions/meta');
        this.register(ToolManagerSkill);

        // Load Custom Skills
        this.loadUserSkills().catch(e => console.error(e));
    }

    public static getInstance(): SkillRegistry {
        if (!SkillRegistry.instance) {
            SkillRegistry.instance = new SkillRegistry();
        }
        return SkillRegistry.instance;
    }

    public register(skill: Skill) {
        if (this.skills.has(skill.id)) {
            console.warn(`Skill ${skill.id} is already registered. Overwriting.`);
        }
        this.skills.set(skill.id, skill);
    }

    public getSkill(id: string): Skill | undefined {
        return this.skills.get(id);
    }

    public getAllSkills(): Skill[] {
        return Array.from(this.skills.values());
    }

    /**
     * Load custom skills from FileSystem
     */
    public async loadUserSkills() {
        try {
            const { UserSkillsStorage } = await import('./storage');
            const storedSkills = await UserSkillsStorage.loadSkills();

            storedSkills.forEach(data => {
                const skill = UserSkillsStorage.hydrateSkill(data);
                // Force category
                skill.category = (data.category as any) || 'user';
                this.register(skill);
            });
            console.log(`[SkillRegistry] Loaded ${storedSkills.length} custom skills.`);
        } catch (e) {
            console.error('[SkillRegistry] Failed to load user skills:', e);
        }
    }

    /**
     * Reload (for ToolManager updates)
     */
    public async reloadUserSkills() {
        await this.loadUserSkills();
        // Since Map.set overwrites, we just re-register. 
        // Deleted skills won't be removed from Map unless we clear or track them.
        // For MVP, we might need to verify if we need to purge old 'user' skills first.

        // Simple purge strategy:
        const currentSkills = this.getAllSkills();
        const userSkillIds = currentSkills.filter(s => s.category === 'user' || s.category === 'model').map(s => s.id);

        // Remove all user skills from map
        userSkillIds.forEach(id => this.skills.delete(id));

        // Re-load
        await this.loadUserSkills();
    }


    /**
     * Remove skills belonging to a specific MCP server
     */
    public removeSkillsByServer(serverId: string) {
        const skillsToDelete = this.getAllSkills().filter(s => s.mcpServerId === serverId);
        skillsToDelete.forEach(s => this.skills.delete(s.id));
        console.log(`[SkillRegistry] Removed ${skillsToDelete.length} skills for server ${serverId}`);
    }

    /**
     * Get enabled skills based on user configuration.
     * Currently returns all skills, but ready for future filtering.
     */
    public getEnabledSkills(config?: any): Skill[] {
        const settings = useSettingsStore.getState();
        const skillsConfig = settings.skillsConfig || {};

        return this.getAllSkills().filter(skill => {
            // Default to enabled if not explicitly disabled
            // Strict check for false, so undefined/true means enabled
            return skillsConfig[skill.id] !== false;
        });
    }

    /**
     * 🆕 Phase 3: 基于模型能力的动态工具路由
     * 
     * @param options.nativeWebSearch - 模型是否支持原生联网（Gemini/Google Vertex）
     * @returns 过滤后的技能列表
     * 
     * 设计决策：
     * - 对于支持原生联网的模型，**无条件**移除 search_internet 工具
     * - 因为这些模型可以自主判断何时需要联网，使用其内置 Grounding 能力
     * - 对于其他模型，保留 search_internet 让模型通过工具调用进行搜索
     */
    public getEnabledSkillsForModel(options: {
        nativeWebSearch?: boolean;
    } = {}): Skill[] {
        const baseSkills = this.getEnabledSkills();
        const { nativeWebSearch } = options;

        // 🔑 核心逻辑：如果模型支持原生联网，则从工具列表中移除 search_internet
        // 模型将使用其内置的 Grounding 能力自主搜索
        if (nativeWebSearch) {
            console.log('[SkillRegistry] Native web search provider detected, removing search_internet tool');
            return baseSkills.filter(skill => skill.id !== 'search_internet');
        }

        return baseSkills;
    }
}

export const skillRegistry = SkillRegistry.getInstance();
