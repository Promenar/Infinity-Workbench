import { Skill } from '../../types/skills';
import { useSettingsStore } from '../../store/settings-store';
import { coreSkills } from './definitions';
import { ShowDebugToastSkill } from './core/debug';
import { TaskManagementSkill } from './core/task'; // ✅ New Import

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
}

export const skillRegistry = SkillRegistry.getInstance();
