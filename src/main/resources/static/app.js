const chatArea = document.getElementById('chatArea');
const chatForm = document.getElementById('chatForm');
const userPromptInput = document.getElementById('userPrompt');
const sendBtn = document.getElementById('sendBtn');
const btnStream = document.getElementById('btnStream');
const btnCall = document.getElementById('btnCall');

let useStream = true;

btnStream.addEventListener('click', () => {
    useStream = true;
    btnStream.classList.add('active');
    btnCall.classList.remove('active');
});

btnCall.addEventListener('click', () => {
    useStream = false;
    btnCall.classList.add('active');
    btnStream.classList.remove('active');
});

function addMessage(text, role) {
    const div = document.createElement('div');
    div.className = `message ${role}`;
    div.textContent = text;
    chatArea.appendChild(div);
    chatArea.scrollTop = chatArea.scrollHeight;
    return div;
}

function buildRequestBody(userPrompt) {
    const body = {
        conversationId: document.getElementById('conversationId').value || 'test-1',
        userPrompt: userPrompt
    };

    const systemPrompt = document.getElementById('systemPrompt').value.trim();
    if (systemPrompt) {
        body.systemPrompt = systemPrompt;
    }

    return body;
}

async function sendCall(userPrompt) {
    const body = buildRequestBody(userPrompt);

    const response = await fetch('/rag/call', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(body)
    });

    if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${await response.text()}`);
    }

    const data = await response.json();
    const text = data.result?.output?.text || JSON.stringify(data, null, 2);
    addMessage(text, 'assistant');
}

async function sendStream(userPrompt) {
    const body = buildRequestBody(userPrompt);

    const response = await fetch('/rag/stream', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(body)
    });

    if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${await response.text()}`);
    }

    const assistantDiv = addMessage('', 'assistant');
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
        const {done, value} = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, {stream: true});
        const lines = buffer.split('\n');
        buffer = lines.pop(); // 마지막 불완전한 라인은 버퍼에 보관

        for (const line of lines) {
            if (line.startsWith('data:')) {
                assistantDiv.textContent += line.slice(5);
            }
        }

        chatArea.scrollTop = chatArea.scrollHeight;
    }

    // 버퍼에 남은 마지막 데이터 처리
    if (buffer.startsWith('data:')) {
        assistantDiv.textContent += buffer.slice(5);
    }

    if (!assistantDiv.textContent) {
        assistantDiv.textContent = '(빈 응답)';
    }
}

chatForm.addEventListener('submit', async (e) => {
    e.preventDefault();

    const userPrompt = userPromptInput.value.trim();
    if (!userPrompt) return;

    addMessage(userPrompt, 'user');
    userPromptInput.value = '';
    sendBtn.disabled = true;

    try {
        if (useStream) {
            await sendStream(userPrompt);
        } else {
            await sendCall(userPrompt);
        }
    } catch (error) {
        addMessage(error.message, 'error');
    } finally {
        sendBtn.disabled = false;
        userPromptInput.focus();
    }
});
