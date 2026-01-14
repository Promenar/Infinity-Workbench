
import { QueryVectorDbSkill, SaveCoreMemorySkill, SearchInternetSkill, GenerateImageSkill } from './core';
import { WriteFileSkill, ReadFileSkill, ListDirSkill } from './filesystem';

export const coreSkills = [
    QueryVectorDbSkill,
    SaveCoreMemorySkill,
    SearchInternetSkill,
    GenerateImageSkill,
    WriteFileSkill,
    ReadFileSkill,
    ListDirSkill,
];

export * from './core';
export * from './filesystem';
