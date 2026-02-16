import React, { useRef, useEffect, useState, useMemo, useCallback } from 'react';
import { View, ActivityIndicator, StyleSheet } from 'react-native';
import { WebView } from 'react-native-webview';
import { useTheme } from '../../theme/ThemeProvider';
import { graphStore, KGNode, KGEdge } from '../../lib/rag/graph-store';
import { VIS_NETWORK_SOURCE } from '../../assets/libs/vis-network-source';
import { Typography } from '../ui';
import { KGNodeEditModal } from './KGNodeEditModal';
import { KGEdgeEditModal } from './KGEdgeEditModal';
import { Plus, Link as LinkIcon, Unlink } from 'lucide-react-native';
import { TouchableOpacity, Alert } from 'react-native';
import * as Haptics from '../../lib/haptics';
import { useI18n } from '../../lib/i18n';

const HTML_TEMPLATE_CACHE: { [key: string]: string } = {};

function generateHtmlKey(isDark: boolean, primaryColor: string): string {
  return `${isDark}-${primaryColor}`;
}

function buildHtmlTemplate(isDark: boolean, colors: any): string {
  const key = generateHtmlKey(isDark, colors[500]);
  if (HTML_TEMPLATE_CACHE[key]) {
    return HTML_TEMPLATE_CACHE[key];
  }

  const template = `
    <!DOCTYPE html>
    <html>
    <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
        <meta http-equiv="Content-Security-Policy" content="default-src * 'unsafe-inline' 'unsafe-eval'; script-src * 'unsafe-inline' 'unsafe-eval'; connect-src * 'unsafe-inline'; img-src * data: blob: 'unsafe-inline'; frame-src *; style-src * 'unsafe-inline';">
        <script type="text/javascript">
          window.onerror = function(message, source, lineno, colno, error) {
            window.ReactNativeWebView.postMessage(JSON.stringify({
              type: 'error',
              message: message,
              source: source,
              lineno: lineno
            }));
          };
          // INJECT_VIS_Here
          if (typeof vis === 'undefined') {
             window.ReactNativeWebView.postMessage(JSON.stringify({
               type: 'error',
               message: 'Vis library failed to load: vis is undefined',
               source: 'inline',
               lineno: 0
             }));
          } else {
             window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'log', message: 'Vis library loaded successfully' }));
          }
        </script>
        <style type="text/css">
            html, body {
                width: 100%;
                height: 100%;
                margin: 0;
                padding: 0;
                background-color: ${isDark ? '#000000' : '#ffffff'};
                overflow: hidden;
            }
            #mynetwork {
                width: 100%;
                height: 100%;
            }
        </style>
    </head>
    <body>
        <div id="mynetwork"></div>
        <script type="text/javascript">
            // DATA_PLACEHOLDER
        </script>
    </body>
    </html>
    `;

  const finalTemplate = template.replace('// INJECT_VIS_Here', VIS_NETWORK_SOURCE);
  HTML_TEMPLATE_CACHE[key] = finalTemplate;
  return finalTemplate;
}

interface KnowledgeGraphViewProps {
  onNodeSelect?: (nodeId: string) => void;
  docIds?: string[]; // 支持多个文档 ID (Changed from single docId to array)
  sessionId?: string;
  agentId?: string;
}

export const KnowledgeGraphView: React.FC<KnowledgeGraphViewProps> = ({
  onNodeSelect,
  docIds,
  sessionId,
  agentId,
}) => {
  const { isDark, colors } = useTheme();
  const { t } = useI18n();
  const webViewRef = useRef<WebView>(null);
  const [graphData, setGraphData] = useState<{ nodes: any[]; edges: any[] } | null>(null);
  const [loading, setLoading] = useState(true);

  // Interaction State
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [selectedNodeData, setSelectedNodeData] = useState<{ id: string; label: string; group?: string } | null>(null);
  const [isEditModalVisible, setIsEditModalVisible] = useState(false);

  // Editing State
  const [isLinkMode, setIsLinkMode] = useState(false);
  const [linkSourceId, setLinkSourceId] = useState<string | null>(null);
  const [selectedEdgeData, setSelectedEdgeData] = useState<{ id: string; label: string; from: string; to: string } | null>(null);
  const [isEdgeModalVisible, setIsEdgeModalVisible] = useState(false);

  // 组件卸载时清理资源，防止崩溃 (必须位于条件返回之前)
  useEffect(() => {
    return () => {
      const stopScript = `
        if (typeof network !== 'undefined' && network) {
          network.stopSimulation();
          network.destroy();
          network = null;
        }
      `;
      webViewRef.current?.injectJavaScript(stopScript);
    };
  }, []);

  useEffect(() => {
    loadData();
  }, [JSON.stringify(docIds), sessionId, agentId]); // Deep compare docIds or use JSON string

  const loadData = async () => {
    setLoading(true);
    try {
      const data = await graphStore.getGraphData(docIds, sessionId, agentId);

      // Transform for Vis.js
      // Nodes: { id, label, group, title }
      // Edges: { from, to, label }
      const nodes = data.nodes.map((n) => ({
        id: n.id,
        label: n.name.length > 10 ? n.name.substring(0, 10) + '...' : n.name,
        fullLabel: n.name,
        group: n.type,
        title: `${n.name} (${n.type})`,
      }));

      const edges = data.edges.map((e) => ({
        from: e.sourceId,
        to: e.targetId,
        label: e.relation,
        arrows: 'to',
        font: { size: 10, align: 'middle' },
      }));

      setGraphData({ nodes, edges });
    } catch (e) {
      console.error('[KnowledgeGraph] 图谱数据加载失败:', e);
    } finally {
      setLoading(false);
    }
  };

  const handleNodeClick = async (nodeId: string) => {
    // Link Mode Logic
    if (isLinkMode) {
      if (!linkSourceId) {
        setTimeout(() => {
          setLinkSourceId(nodeId);
          Haptics.selectionAsync();
        }, 10);
        // Ideally show toast here: "Select target node"
      } else {
        if (linkSourceId === nodeId) {
          setLinkSourceId(null); // Cancel
          return;
        }
        // Create Edge
        try {
          await graphStore.createEdge(linkSourceId, nodeId, 'related_to', undefined, 1.0, { sessionId, agentId });
          Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
          setLinkSourceId(null);
          loadData(); // Refresh
          // Optional: Toast success?
        } catch (e: any) {
          console.error(e);
          Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
          Alert.alert(t.common.error, `${t.rag.kg.linkCreateFailed || 'Failed to create link'}: ${e.message || 'Unknown error'}`);
        }
      }
      return;
    }

    // Normal Edit Logic
    const node = graphData?.nodes.find(n => n.id === nodeId);
    if (node) {
      setSelectedNodeData({ id: node.id, label: node.fullLabel || node.label, group: node.group });
      setSelectedNodeId(nodeId);
      setIsEditModalVisible(true);
    }

    if (onNodeSelect) {
      onNodeSelect(nodeId);
    }
  };

  const handleEdgeClick = (edgeId: string) => {
    if (isLinkMode) return;
    const edge = graphData?.edges.find(e => e.id === edgeId);
    if (edge) {
      setSelectedEdgeData(edge);
      setIsEdgeModalVisible(true);
    }
  }

  const handleCreateNode = () => {
    setSelectedNodeData(null); // Clear for creation
    setIsEditModalVisible(true);
  };

  const handleReload = () => {
    loadData();
    setIsEditModalVisible(false);
    setIsEdgeModalVisible(false);
    setSelectedNodeId(null);
    setSelectedEdgeData(null);
  };

  if (loading) {
    return (
      <View style={styles.center}>
        <ActivityIndicator size="large" color={colors[500]} />
        <Typography style={{ marginTop: 10, color: isDark ? '#fff' : '#000' }}>
          {t.rag.kg.loadingGraph}
        </Typography>
      </View>
    );
  }

  if (!graphData || graphData.nodes.length === 0) {
    return (
      <View style={styles.center}>
        <Typography style={{ color: isDark ? '#fff' : '#000' }}>
          {t.rag.kg.noData}
        </Typography>
        <Typography variant="label" style={{ marginTop: 8 }}>
          {t.rag.kg.noDataDesc}
        </Typography>
      </View>
    );
  }

  const dataScript = `
            var nodes = new vis.DataSet(${JSON.stringify(graphData.nodes)});
            var edges = new vis.DataSet(${JSON.stringify(graphData.edges)});

            var container = document.getElementById('mynetwork');
            var data = {
                nodes: nodes,
                edges: edges
            };
            var options = {
                nodes: {
                    shape: 'dot',
                    size: 16,
                    font: {
                        color: '${isDark ? '#ffffff' : '#000000'}',
                        size: 14
                    },
                    borderWidth: 2,
                    color: {
                        background: '${isDark ? '#333333' : '#eeeeee'}',
                        border: '${colors[500]}',
                        highlight: {
                             background: '#818cf8',
                             border: '#4338ca'
                        }
                    }
                },
                edges: {
                    color: {
                        color: '${isDark ? '#555555' : '#cccccc'}',
                        highlight: '${colors[500]}'
                    },
                    width: 1,
                    smooth: {
                        type: 'continuous'
                    }
                },
                physics: {
                    stabilization: false,
                    barnesHut: {
                        gravitationalConstant: -8000,
                        springConstant: 0.04,
                        springLength: 95
                    }
                },
                layout: {
                    improvedLayout: true
                },
                groups: {
                    person: { color: { background: '#fca5a5', border: '#ef4444' } },
                    org: { color: { background: '#93c5fd', border: '#3b82f6' } },
                    location: { color: { background: '#86efac', border: '#22c55e' } },
                    concept: { color: { background: '#fcd34d', border: '#f59e0b' } }
                }
            };
            
            var network = new vis.Network(container, data, options);

            network.on("click", function (params) {
                if (params.nodes.length > 0) {
                     window.ReactNativeWebView.postMessage(JSON.stringify({
                         type: 'nodeSelect',
                         nodeId: params.nodes[0]
                     }));
                } else if (params.edges.length > 0) {
                     window.ReactNativeWebView.postMessage(JSON.stringify({
                         type: 'edgeSelect',
                         edgeId: params.edges[0]
                     }));
                }
            });
        `;

  const htmlTemplate = buildHtmlTemplate(isDark, colors);
  const finalHtml = htmlTemplate.replace('// DATA_PLACEHOLDER', dataScript);

  return (
    <View style={{ flex: 1 }}>
      <WebView
        ref={webViewRef}
        source={{ html: finalHtml }}
        style={{ flex: 1, backgroundColor: 'transparent', opacity: loading ? 0 : 1 }}
        androidLayerType="hardware" // Ensure acceleration
        onMessage={(event) => {
          try {
            const data = JSON.parse(event.nativeEvent.data);
            if (data.type === 'nodeSelect') {
              if (onNodeSelect || true) handleNodeClick(data.nodeId);
            } else if (data.type === 'edgeSelect') {
              handleEdgeClick(data.edgeId);
            } else if (data.type === 'error') {
              console.error('WebView Error:', data.message, 'at', data.source, 'line', data.lineno);
            } else if (data.type === 'log') {
              console.log('WebView Log:', data.message);
            }
          } catch (e) {
            // ignore
          }
        }}
      />

      <KGNodeEditModal
        visible={isEditModalVisible}
        node={selectedNodeData}
        onClose={() => setIsEditModalVisible(false)}
        onSave={handleReload}
        sessionId={sessionId}
        agentId={agentId}
      />

      <KGEdgeEditModal
        visible={isEdgeModalVisible}
        edge={selectedEdgeData}
        onClose={() => setIsEdgeModalVisible(false)}
        onSave={handleReload}
      />

      {/* Floating Action Buttons */}
      <View style={{ position: 'absolute', bottom: 30, right: 20, gap: 12 }}>
        {/* Link Mode Toggle */}
        <TouchableOpacity
          onPress={() => {
            setTimeout(() => {
              setIsLinkMode(!isLinkMode);
              setLinkSourceId(null);
              Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
            }, 10);
          }}
          style={{
            backgroundColor: isLinkMode ? colors[500] : (isDark ? '#27272a' : '#ffffff'),
            width: 50,
            height: 50,
            borderRadius: 25,
            justifyContent: 'center',
            alignItems: 'center',
            shadowColor: "#000",
            shadowOffset: { width: 0, height: 2 },
            shadowOpacity: 0.25,
            shadowRadius: 3.84,
            elevation: 5,
            borderWidth: isLinkMode ? 0 : 1,
            borderColor: isDark ? '#3f3f46' : '#e5e7eb'
          }}
        >
          {isLinkMode ? <Unlink size={24} color="#fff" /> : <LinkIcon size={24} color={isDark ? '#fff' : '#000'} />}
        </TouchableOpacity>

        {/* Create Node FAB */}
        <TouchableOpacity
          onPress={handleCreateNode}
          style={{
            backgroundColor: colors[500],
            width: 56,
            height: 56,
            borderRadius: 28,
            justifyContent: 'center',
            alignItems: 'center',
            shadowColor: "#000",
            shadowOffset: { width: 0, height: 2 },
            shadowOpacity: 0.25,
            shadowRadius: 3.84,
            elevation: 5
          }}
        >
          <Plus size={30} color="#fff" />
        </TouchableOpacity>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  center: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
});
