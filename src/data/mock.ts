import { MessageSquare, FileText } from 'lucide-react-native';

export interface DocItem {
    id: string;
    title: string;
    size: string;
    date: string;
    type: string;
    folderId?: string;
}

export const MOCK_CONVERSATIONS = [
    { id: '1', title: 'NeuralFlow Assistant', subtitle: 'I can help you analyze that data.', time: '12:30 PM', unread: 0, color: '#3b82f6' },
    { id: '2', title: 'Brainstorming Group', subtitle: 'Let’s meet tomorrow at 10. We need to discuss the new roadmap.', time: 'Yesterday', unread: 2, color: '#8b5cf6' },
    { id: '3', title: 'React Expert', subtitle: 'Dependency array needs to include the callback function to solve the memoization issue.', time: 'Mon', unread: 0, color: '#10b981' },
    { id: '4', title: 'Weekly Report', subtitle: 'Attached is the summary of our team performance for Q4.', time: 'Fri', unread: 0, color: '#f59e0b' },
    { id: '5', title: 'Design Review', subtitle: 'Can we change the color scheme to something more vibrant? I prefer Lumina style.', time: 'Thu', unread: 4, color: '#6366f1' },
    { id: '6', title: 'Product Launch', subtitle: 'The landing page is almost ready. Check the latest Vercel preview.', time: '10:15 AM', unread: 0, color: '#ec4899' },
    { id: '7', title: 'Alex Riviera', subtitle: 'Hey! Are we still on for the coffee meeting?', time: 'Oct 24', unread: 1, color: '#06b6d4' },
    { id: '8', title: 'Code Review #128', subtitle: 'LGTM! Just fix that one minor lint error in the layout file.', time: 'Oct 22', unread: 0, color: '#f97316' },
    { id: '9', title: 'System Logs', subtitle: 'Warning: CPU usage exceeded 85% on node-prod-02.', time: 'Oct 21', unread: 0, color: '#ef4444' },
    { id: '10', title: 'Sarah Jenkins', subtitle: 'The presentation slides for the board meeting are finally done.', time: 'Oct 20', unread: 0, color: '#8b5cf6' },
    { id: '11', title: 'Tech Support', subtitle: 'Your ticket #9421 has been marked as resolved.', time: 'Oct 19', unread: 0, color: '#64748b' },
    { id: '12', title: 'Investment Group', subtitle: 'Bitcoin cleared 100k! Time to review our positions.', time: 'Oct 18', unread: 5, color: '#eab308' },
    { id: '13', title: 'Home Security', subtitle: 'Motion detected in the Backyard at 2:45 AM.', time: 'Oct 17', unread: 0, color: '#10b981' },
    { id: '14', title: 'Fitness Coach', subtitle: 'New metabolic conditioning workout uploaded for you.', time: 'Oct 16', unread: 0, color: '#f43f5e' },
    { id: '15', title: 'Travel Concierge', subtitle: 'Your boarding pass for flight LH402 is ready.', time: 'Oct 15', unread: 2, color: '#3b82f6' },
    { id: '16', title: 'Project Lumina', subtitle: 'The glassmorphism borders need a bit more subtle translucency.', time: 'Oct 14', unread: 0, color: '#6366f1' },
    { id: '17', title: 'Backend API', subtitle: 'New endpoint documentation for RAG integration is live.', time: 'Oct 13', unread: 0, color: '#10b981' },
    { id: '18', title: 'Family Sync', subtitle: 'Dad: "Who left the lights on again?"', time: 'Oct 12', unread: 12, color: '#f59e0b' },
    { id: '19', title: 'Beta Testers', subtitle: 'We found a bug in the shared folder permission logic.', time: 'Oct 11', unread: 3, color: '#a855f7' },
    { id: '20', title: 'AI Research', subtitle: 'New paper on sparse attention mechanisms for long context.', time: 'Oct 10', unread: 0, color: '#3b82f6' },
];

export const MOCK_FOLDERS = [
    { id: 'folder_work', label: 'Work', count: '12 items', iconColor: '#6366f1' },
    { id: 'folder_private', label: 'Private', count: '4 items', iconColor: '#818cf8' },
    { id: 'folder_news', label: 'News', count: '8 items', iconColor: '#f59e0b' },
];

export const MOCK_DOCS: DocItem[] = [
    { id: '1', title: 'Startups 101.pdf', size: '2.4 MB', date: 'Yesterday', type: 'pdf', folderId: 'folder_work' },
    { id: '2', title: 'Project Specs.docx', size: '15 KB', date: 'Monday', type: 'doc', folderId: 'folder_work' },
    { id: '3', title: 'Q3 Financials.xlsx', size: '12 MB', date: 'Last week', type: 'xls', folderId: 'folder_work' },
    { id: '4', title: 'Notes.txt', size: '2 KB', date: 'Just now', type: 'txt', folderId: 'folder_private' },
    { id: '5', title: 'Design Guidelines.pdf', size: '5.6 MB', date: 'Oct 23', type: 'pdf', folderId: 'folder_work' },
    { id: '6', title: 'Marketing Strategy.pptx', size: '8.1 MB', date: 'Oct 22', type: 'ppt', folderId: 'folder_work' },
    { id: '7', title: 'Meeting Notes Oct 20.md', size: '4 KB', date: 'Oct 20', type: 'md', folderId: 'folder_private' },
    { id: '8', title: 'User Feedback Batch 1.csv', size: '124 KB', date: 'Oct 19', type: 'csv', folderId: 'folder_work' },
    { id: '9', title: 'Product Roadmap 2026.pdf', size: '1.2 MB', date: 'Oct 18', type: 'pdf', folderId: 'folder_work' },
    { id: '10', title: 'Icon Assets v2.zip', size: '45 MB', date: 'Oct 17', type: 'zip', folderId: 'folder_private' },
    { id: '11', title: 'NeuralFlow Architecture.png', size: '3.4 MB', date: 'Oct 16', type: 'png', folderId: 'folder_work' },
    { id: '12', title: 'API Documentation.html', size: '456 KB', date: 'Oct 15', type: 'html', folderId: 'folder_work' },
    { id: '13', title: 'Employee Handbook.pdf', size: '2.8 MB', date: 'Oct 14', type: 'pdf', folderId: 'folder_work' },
    { id: '14', title: 'Q4 Sales Forecast.xlsx', size: '420 KB', date: 'Oct 13', type: 'xls', folderId: 'folder_work' },
    { id: '15', title: 'Internal Wiki Backup.json', size: '12 MB', date: 'Oct 12', type: 'json', folderId: 'folder_private' },
    { id: '16', title: 'System Architecture.sketch', size: '22 MB', date: 'Oct 11', type: 'sketch', folderId: 'folder_work' },
    { id: '17', title: 'Brand Kit 2025.pdf', size: '15.6 MB', date: 'Oct 10', type: 'pdf', folderId: 'folder_work' },
    { id: '18', title: 'Legal Contract Rev 3.pdf', size: '890 KB', date: 'Oct 09', type: 'pdf', folderId: 'folder_work' },
    { id: '19', title: 'Python Scripts.zip', size: '1.1 MB', date: 'Oct 08', type: 'zip', folderId: 'folder_work' },
    { id: '20', title: 'Frontend Specs.docx', size: '34 KB', date: 'Oct 07', type: 'doc', folderId: 'folder_work' },
];
