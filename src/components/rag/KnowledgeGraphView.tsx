import React, { useRef, useEffect, useState } from 'react';
import { View, ActivityIndicator, StyleSheet } from 'react-native';
import { WebView } from 'react-native-webview';
import { useTheme } from '../../theme/ThemeProvider';
import { graphStore, KGNode, KGEdge } from '../../lib/rag/graph-store';
import { Typography } from '../ui';
import { KGNodeEditModal } from './KGNodeEditModal';

interface KnowledgeGraphViewProps {
  onNodeSelect?: (nodeId: string) => void;
  docIds?: string[]; // Changed from single docId to array
  sessionId?: string;
  agentId?: string;
}

export const KnowledgeGraphView: React.FC<KnowledgeGraphViewProps> = ({
  onNodeSelect,
  docIds,
  sessionId,
  agentId,
}) => {
  const { isDark } = useTheme();
  const webViewRef = useRef<WebView>(null);
  const [graphData, setGraphData] = useState<{ nodes: any[]; edges: any[] } | null>(null);
  const [loading, setLoading] = useState(true);

  // Interaction State
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [selectedNodeData, setSelectedNodeData] = useState<{ id: string; label: string; group?: string } | null>(null);
  const [isEditModalVisible, setIsEditModalVisible] = useState(false);

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
    } finally {
      setLoading(false);
    }
  };

  const handleNodeClick = (nodeId: string) => {
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

  const handleReload = () => {
    loadData();
    setIsEditModalVisible(false);
    setSelectedNodeId(null);
  };

  if (loading) {
    return (
      <View style={styles.center}>
        <ActivityIndicator size="large" color="#6366f1" />
        <Typography style={{ marginTop: 10, color: isDark ? '#fff' : '#000' }}>
          Loading Graph...
        </Typography>
      </View>
    );
  }

  if (!graphData || graphData.nodes.length === 0) {
    return (
      <View style={styles.center}>
        <Typography style={{ color: isDark ? '#fff' : '#000' }}>
          No Knowledge Graph data found.
        </Typography>
        <Typography variant="label" style={{ marginTop: 8 }}>
          Try extracting knowledge from documents first.
        </Typography>
      </View>
    );
  }

  // HTML Template using Vis.js via CDN (Local assets would be better for offline, but CDN is easier for prototype)
  const htmlContent = `
    <!DOCTYPE html>
    <html>
    <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
        <script type="text/javascript" src="https://unpkg.com/vis-network/standalone/umd/vis-network.min.js"></script>
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
            // Data
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
                        border: '#6366f1',
                        highlight: {
                             background: '#818cf8',
                             border: '#4338ca'
                        }
                    }
                },
                edges: {
                    color: {
                        color: '${isDark ? '#555555' : '#cccccc'}',
                        highlight: '#6366f1'
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
                }
            });
        </script>
    </body>
    </html>
    `;

  return (
    <View style={{ flex: 1 }}>
      <WebView
        ref={webViewRef}
        source={{ html: htmlContent }}
        style={{ flex: 1, backgroundColor: 'transparent' }}
        onMessage={(event) => {
          if (onNodeSelect) {
            try {
              const data = JSON.parse(event.nativeEvent.data);
              if (data.type === 'nodeSelect') {
                handleNodeClick(data.nodeId);
              }
            } catch (e) {
              // ignore
            }
          }
        }}
      />

      <KGNodeEditModal
        visible={isEditModalVisible}
        node={selectedNodeData}
        onClose={() => setIsEditModalVisible(false)}
        onSave={handleReload}
      />
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
