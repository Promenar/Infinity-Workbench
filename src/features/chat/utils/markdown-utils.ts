/**
 * 从 Markdown 内容中提取图片
 * @param content Markdown 内容
 * @returns { cleanContent: string, images: Array<{src: string, alt: string}> }
 */
export function extractImagesFromMarkdown(content: string): {
  cleanContent: string;
  images: Array<{ src: string; alt: string }>;
} {
  if (!content) {
    return { cleanContent: '', images: [] };
  }

  const images: Array<{ src: string; alt: string }> = [];

  // 匹配 ![alt](url) 格式的图片
  // 使用非贪婪匹配确保正确解析多张图片
  const imageRegex = /!\[([^\]]*)\]\(([^)]+)\)/g;

  const cleanContent = content.replace(imageRegex, (match, alt, src) => {
    images.push({
      src: src.trim(),
      alt: alt.trim() || 'Generated Image',
    });
    // 移除图片标记，避免 Markdown 库解析问题
    return '';
  });

  return { cleanContent, images };
}
