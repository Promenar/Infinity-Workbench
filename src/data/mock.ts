import { MessageSquare, FileText } from 'lucide-react-native';

export const MOCK_CONVERSATIONS = [
    { id: '1', title: 'NeuralFlow Assistant', subtitle: 'I can help you analyze that data.', time: '12:30 PM', unread: 0, color: '#3b82f6' },
    { id: '2', title: 'Brainstorming Group', subtitle: 'Let’s meet tomorrow at 10.', time: 'Yesterday', unread: 2, color: '#8b5cf6' },
    { id: '3', title: 'React Expert', subtitle: 'Dependency array needs to include...', time: 'Mon', unread: 0, color: '#10b981' },
    { id: '4', title: 'Weekly Report', subtitle: 'Attached is the summary.', time: 'Fri', unread: 0, color: '#f59e0b' },
    { id: '5', title: 'Design Review', subtitle: 'Can we change the color scheme?', time: 'Thu', unread: 4, color: '#6366f1' },
];

export const MOCK_DOCS = [
    { id: '1', title: 'Startups 101.pdf', size: '2.4 MB', date: 'Yesterday', type: 'pdf' },
    { id: '2', title: 'Project Specs.docx', size: '15 KB', date: 'Monday', type: 'doc' },
    { id: '3', title: 'Q3 Financials.xlsx', size: '12 MB', date: 'Last week', type: 'xls' },
    { id: '4', title: 'Notes.txt', size: '2 KB', date: 'Just now', type: 'txt' },
];
