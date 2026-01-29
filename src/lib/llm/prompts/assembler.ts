export class PromptBuilder {
    private parts: string[] = [];

    addPart(content: string): this {
        if (content && content.trim().length > 0) {
            this.parts.push(content);
        }
        return this;
    }

    addModule(moduleContent: string | undefined): this {
        if (moduleContent) {
            this.parts.push(moduleContent);
        }
        return this;
    }

    build(): string {
        return this.parts.join('\n\n');
    }
}
