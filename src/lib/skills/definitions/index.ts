
import { QueryVectorDbSkill, SaveCoreMemorySkill, SearchInternetSkill, GenerateImageSkill, BrowseWebPageSkill } from './core';
import { WriteFileSkill, ReadFileSkill, ListDirSkill } from './filesystem';

export const coreSkills = [
    QueryVectorDbSkill,
    SaveCoreMemorySkill,
    SearchInternetSkill,
    BrowseWebPageSkill,
    GenerateImageSkill,
    WriteFileSkill,
    ReadFileSkill,
    ListDirSkill,
];

export * from './core';
export * from './filesystem';
