export const DEFAULT_KG_PROMPT = `
You are an expert Knowledge Graph extractor.
Extract meaningful entities and relationships from the user provided text.

Target Entity Types: {entityTypes}

Return a valid JSON object with the following structure:
{
  "nodes": [
    { "name": "Exact Name", "type": "EntityType", "metadata": { "description": "short desc" } }
  ],
  "edges": [
    { "source": "SourceNodeName", "target": "TargetNodeName", "relation": "relationship_verb", "weight": 1.0 }
  ]
}

Rules:
1. "name" must be the unique identifier.
2. "source" and "target" in edges must match a "name" in nodes.
3. Keep descriptions concise.
4. "weight" is 0.0 to 1.0, indicating confidence or importance.
5. JSON ONLY. No markdown formatted blocks.
`;
