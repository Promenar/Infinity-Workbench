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
     * 🆕 Phase 4: 获取会话感知的可用技能列表
     * 融合全局禁用控制 (Settings) + 会话级路由控制 (Session Toolbox)
     */
    public getEnabledSkillsForSession(
        session: {
            activeMcpServerIds?: string[];
            activeSkillIds?: string[];
            options?: { toolsEnabled?: boolean; webSearch?: boolean };
        },
        options: {
            nativeWebSearch?: boolean;
        } = {}
    ): Skill[] {
        // 1. 全局开关检查 (Master Toggle)
        const toolsEnabled = session.options?.toolsEnabled ?? true;
        if (!toolsEnabled) return [];

        // 2. 获取全局启用的基础技能 (Settings 控制)
        const baseSkills = this.getEnabledSkills();

        // 3. 应用会话级路由规则
        const activeMcpIds = session.activeMcpServerIds || [];
        const activeSkillIds = session.activeSkillIds || [];

        const sessionSkills = baseSkills.filter(s => {
            // A. MCP 工具：必须隶属于当前会话已激活的服务器
            if (s.mcpServerId) {
                return activeMcpIds.includes(s.mcpServerId);
            }

            // B. 自定义技能 (User Category)：必须在当前会话激活
            if (s.category === 'user') {
                return activeSkillIds.includes(s.id);
            }

            // C. 内置技能 (Preset)：直接受全局 Settings 控制 (baseSkills 已经处理)
            return true;
        });

        // 4. 动态工具路由 (针对原生联网优化)
        if (options.nativeWebSearch) {
            return sessionSkills.filter(skill => skill.id !== 'search_internet');
        }

        return sessionSkills;
    }

    public getEnabledSkillsForModel(options: {
        nativeWebSearch?: boolean;
    } = {}): Skill[] {
        const baseSkills = this.getEnabledSkills();
        const { nativeWebSearch } = options;

        if (nativeWebSearch) {
            return baseSkills.filter(skill => skill.id !== 'search_internet');
        }

        return baseSkills;
    }
}

export const skillRegistry = SkillRegistry.getInstance();
