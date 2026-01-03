# Document Library Refactor Plan (Phase 4)

## Goal
Transform the basic file list into a robust "Resource Manager" style document library with batch operations, encoding support, and better UX.

## 1. Encoding Fix (P0) - "The Garbled Text Killer"
**Problem**: `FileSystem.readAsStringAsync` assumes UTF-8. Windows ANSI (GBK) files become garbage.
**Solution**:
1. Install `iconv-lite` and `buffer`.
2. Create `src/lib/file-utils.ts`:
   - `readFileContent(uri: string): Promise<string>`
   - Reads as Base64.
   - Converts to Buffer.
   - Detects encoding (Auto-detect or Fallback to GBK if UTF-8 fails).
   - Decodes string.

## 2. Store Enhancement (P1)
**File**: `src/store/rag-store.ts`
**Changes**:
- Implement `deleteBatch(docIds: string[])`.
- Implement `vectorizeBatch(docIds: string[])`.
- Add `clearAllVectors()` (already done? verify).

## 3. UI Overhaul (P1)
**File**: `app/(tabs)/rag.tsx`
**Changes**:
- **Batch Mode**:
    - Long press to enter "Selection Mode".
    - Bottom Action Bar: [Delete] [Re-vectorize] [Move].
- **Import Flow**:
    - Use new `readFileContent` utility.
    - Show parsing progress if importing multiple files.
- **Drag & Drop** (Experimental):
    - Investigate `expo-drag-drop-content-view` or similar for tablet/desktop targets. (Note: React Native Web DnD is different).

### Step 5: Drag & Drop Import (P2)
- [ ] Install `expo-drag-drop-content-view`.
- [ ] Configure `android/build.gradle` if necessary.
- [ ] Implement `DragDropContentView` wrapper in `rag.tsx`.
- [ ] Handle `onDrop` event to feed into `handleFileImport`.
- [ ] Install `iconv-lite`, `buffer`.
- [ ] Create `src/lib/file-utils.ts`.

### Step 2: Fix Import Logic inside `rag.tsx`
- [ ] Replace `FileSystem.readAsStringAsync` with `fileUtils.readFileContent`.
- [ ] Test with GBK text file.

### Step 3: Verify Store Batch Actions
- [ ] Implement/Fix `deleteBatch` in `rag-store.ts`.
- [ ] Implement `reVectorizeBatch`.

### Step 6: Resource Manager UI (Phase 5)
- [x] **Navigation Model Change**:
    - Switch from Recursive Accordion (`FolderTree`) to Drill-Down Explorer.
    - State: `currentFolderId` (Store or Local).
- [x] **Components**:
    - Create `BreadcrumbBar` (Home > Folder A > Folder B).
    - Create `FileList` (Flat list of current folder items).
    - Update `CompactDocItem` & `FolderItem` to support new interaction model.
- [x] **Interactions**:
    - Click Folder -> Enter Folder.
    - Click File -> Open (or Select if mode active).
    - Back Button -> Go to Parent.
- [x] **Drag & Drop**:
    - Drop on Folder Item -> Move to that folder. (Partially via Move Modal)
    - Drop on Empty Space -> Import to current folder.

### Step 7: 核心解析能力升级 (Phase 6)
- [ ] **多文件拖拽支持**:
    - [ ] 更新 `handleDrop` 以遍历 `event.assets`。
    - [ ] 实现顺序处理队列 (`sequential processing queue`) 以防止 UI 冻结。
    - [ ] 添加批量操作的 成功/失败 Toast 汇总。
- [ ] **PDF 支持 (仅文本)**:
    - [ ] 集成策略: 使用 `react-native-pdf` 或 `pdfjs-dist` (必要时通过 WebView bridge)。
    - [ ] 提取逻辑: 提取文本内容用于向量化。
    - [ ] UI: 为 PDF 文件显示特定图标。
    - [ ] *限制*: 仅支持含文本层的 PDF (暂不支持扫描件 OCR)。

### Step 8: 可视化与反馈 (Phase 7)
- [ ] **RAG 过程可视化**:
    - [ ] 创建 `RagStatusIndicator` 组件 (悬浮或工具栏)。
    - [ ] 视觉状态: `队列中` -> `解析中` -> `切块中` -> `向量化` -> `存储中`。
    - [ ] 批量操作进度条。
- [ ] **向量库健康看板**:
    - [ ] 可视化 "冗余" (重复/孤立的向量)。
    - [ ] "清理预览": 在确认前展示将被删除的内容。

### Step 9: 高级知识能力 (Phase 8 - Future)
- [ ] **多模态 RAG (图片)**:
    - [ ] 流程: 上传图片 -> 调用 VLM (通用视觉模型) -> 生成描述 -> 向量化描述。
    - [ ] 搜索: 文本查询匹配图片描述。
- [ ] **智能标签**:
    - [ ] 基于文档摘要自动生成标签。
    - [ ] 按标签筛选文库。
