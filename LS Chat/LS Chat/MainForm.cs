using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Windows.Forms;

namespace LS_Chat
{
    /// <summary>
    /// 主窗体：左侧用户/群组列表，右侧聊天区。
    /// 关闭时最小化到系统托盘；WebSocket 模式走 WSS 长连接实时推送（断线自动重连），
    /// HTTP 模式每 3 秒轮询新消息，均弹气球通知。
    /// </summary>
    public class MainForm : Form
    {
        private const int PollIntervalMs = 3000;
        private const int BackupPollIntervalMs = 30000;
        private const int MaxImageHeight = 240;
        private const int MaxImageWidth = 320;

        private readonly AppConfig _config;
        private readonly ApiClient _api;
        private readonly NotifyIcon _tray;
        private readonly Timer _pollTimer;

        private readonly ListBox _userList;
        private readonly ListBox _groupList;
        private readonly Label _lblPeer;
        private readonly ListBox _messages;
        private readonly TextBox _input;
        private readonly Button _btnSend;
        private readonly Button _btnImage;
        private readonly PictureBox _selfAvatar;

        private readonly Dictionary<long, string> _userNames = new Dictionary<long, string>();
        private readonly Dictionary<long, string> _groupNames = new Dictionary<long, string>();
        private readonly Dictionary<long, string> _groupRoles = new Dictionary<long, string>();
        private readonly Dictionary<long, Image> _avatars = new Dictionary<long, Image>();
        private long _currentPeerId = -1;
        private long _currentGroupId = -1;
        private bool _polling;
        private int _refreshCounter;
        private bool _reallyExit;
        private readonly System.Threading.CancellationTokenSource _cts =
            new System.Threading.CancellationTokenSource();

        /// <summary>用户点击注销时为 true，Program 据此返回登录界面。</summary>
        public bool LoggedOut { get; private set; }

        public MainForm(AppConfig config, ApiClient api)
        {
            _config = config;
            _api = api;

            Text = "LS Chat - " + config.UserName;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(900, 580);
            MinimumSize = new Size(720, 500);

            // ---------- 顶部工具条 ----------
            var topBar = new Panel { Dock = DockStyle.Top, Height = 42 };
            _selfAvatar = new PictureBox
            {
                Location = new Point(6, 5),
                Size = new Size(32, 32),
                SizeMode = PictureBoxSizeMode.Zoom,
                BorderStyle = BorderStyle.FixedSingle,
                Cursor = Cursors.Hand
            };
            _selfAvatar.Click += async (s, e) => await ChangeAvatar();
            var lblSelf = new Label
            {
                Text = "当前用户：" + config.UserName + "（我的ID: " + config.UserId + "）",
                AutoSize = true,
                Location = new Point(44, 13)
            };
            var btnProfile = new Button { Text = "资料", Dock = DockStyle.Right, Width = 70 };
            btnProfile.Click += async (s, e) =>
            {
                using (var profileForm = new ProfileForm(_api))
                {
                    profileForm.ShowDialog(this);
                }
                await RefreshUsers();
            };
            var btnLogout = new Button { Text = "注销", Dock = DockStyle.Right, Width = 70 };
            btnLogout.Click += (s, e) =>
            {
                LoggedOut = true;
                _reallyExit = true;
                Close();
            };
            topBar.Controls.Add(btnProfile);
            topBar.Controls.Add(btnLogout);
            topBar.Controls.Add(lblSelf);
            topBar.Controls.Add(_selfAvatar);

            // ---------- 左右分栏 ----------
            var split = new SplitContainer
            {
                Dock = DockStyle.Fill,
                SplitterDistance = 210,
                FixedPanel = FixedPanel.Panel1
            };

            // 左侧：联系人 / 群组 选项卡
            var leftTabs = new TabControl { Dock = DockStyle.Fill };

            _userList = new ListBox
            {
                Dock = DockStyle.Fill,
                IntegralHeight = false,
                DrawMode = DrawMode.OwnerDrawVariable,
                ItemHeight = 48
            };
            _userList.SelectedIndexChanged += UserList_SelectedIndexChanged;
            _userList.MeasureItem += UserList_MeasureItem;
            _userList.DrawItem += UserList_DrawItem;
            var userPanel = new Panel { Dock = DockStyle.Fill };
            var userButtons = new Panel { Dock = DockStyle.Top, Height = 36 };
            var btnAddFriend = new Button { Text = "加好友", Dock = DockStyle.Left, Width = 80 };
            btnAddFriend.Click += BtnAddFriend_Click;
            var btnFriendRequests = new Button { Text = "好友申请", Dock = DockStyle.Left, Width = 80 };
            btnFriendRequests.Click += FriendRequests_Click;
            userButtons.Controls.Add(btnFriendRequests);
            userButtons.Controls.Add(btnAddFriend);
            userPanel.Controls.Add(_userList);
            userPanel.Controls.Add(userButtons);
            var tabUsers = new TabPage("联系人");
            tabUsers.Controls.Add(userPanel);

            var groupPanel = new Panel { Dock = DockStyle.Fill };
            var groupButtons = new Panel { Dock = DockStyle.Top, Height = 72 };
            var groupRow1 = new Panel { Dock = DockStyle.Top, Height = 36 };
            var groupRow2 = new Panel { Dock = DockStyle.Top, Height = 36 };
            var btnCreateGroup = new Button { Text = "创建群", Dock = DockStyle.Left, Width = 80 };
            btnCreateGroup.Click += BtnCreateGroup_Click;
            var btnSearchGroup = new Button { Text = "搜索加入", Dock = DockStyle.Left, Width = 80 };
            btnSearchGroup.Click += BtnSearchGroup_Click;
            var btnManageGroup = new Button { Text = "管理群", Dock = DockStyle.Left, Width = 80 };
            btnManageGroup.Click += GroupManage_Click;
            var btnGroupRequests = new Button { Text = "入群申请", Dock = DockStyle.Left, Width = 80 };
            btnGroupRequests.Click += GroupRequests_Click;
            groupRow1.Controls.Add(btnSearchGroup);
            groupRow1.Controls.Add(btnCreateGroup);
            groupRow2.Controls.Add(btnGroupRequests);
            groupRow2.Controls.Add(btnManageGroup);
            groupButtons.Controls.Add(groupRow2);
            groupButtons.Controls.Add(groupRow1);
            _groupList = new ListBox { Dock = DockStyle.Fill, IntegralHeight = false };
            _groupList.SelectedIndexChanged += GroupList_SelectedIndexChanged;
            var groupMenu = new ContextMenuStrip();
            groupMenu.Items.Add("管理群组", null, GroupManage_Click);
            groupMenu.Items.Add("入群申请", null, GroupRequests_Click);
            _groupList.ContextMenuStrip = groupMenu;
            groupPanel.Controls.Add(_groupList);
            groupPanel.Controls.Add(groupButtons);
            var tabGroups = new TabPage("群组");
            tabGroups.Controls.Add(groupPanel);

            leftTabs.TabPages.Add(tabUsers);
            leftTabs.TabPages.Add(tabGroups);
            split.Panel1.Controls.Add(leftTabs);

            // 右侧：会话标题 + 消息列表 + 输入区
            var rightPanel = new Panel { Dock = DockStyle.Fill };
            _lblPeer = new Label
            {
                Text = "请选择左侧用户或群组开始聊天",
                Dock = DockStyle.Top,
                Height = 32,
                TextAlign = ContentAlignment.MiddleLeft,
                Padding = new Padding(8, 0, 0, 0)
            };

            var bottomPanel = new Panel { Dock = DockStyle.Bottom, Height = 42 };
            _btnImage = new Button { Text = "图片", Dock = DockStyle.Left, Width = 60 };
            _btnImage.Click += BtnImage_Click;
            var btnVideo = new Button { Text = "视频", Dock = DockStyle.Left, Width = 60 };
            btnVideo.Click += BtnVideo_Click;
            _btnSend = new Button { Text = "发送", Dock = DockStyle.Right, Width = 60 };
            _btnSend.Click += BtnSend_Click;
            _input = new TextBox { Dock = DockStyle.Fill };
            _input.KeyDown += (s, e) =>
            {
                if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; BtnSend_Click(s, e); }
            };
            bottomPanel.Controls.Add(_input);
            bottomPanel.Controls.Add(btnVideo);
            bottomPanel.Controls.Add(_btnImage);
            bottomPanel.Controls.Add(_btnSend);

            _messages = new ListBox
            {
                Dock = DockStyle.Fill,
                DrawMode = DrawMode.OwnerDrawVariable,
                IntegralHeight = false,
                HorizontalScrollbar = false
            };
            _messages.MeasureItem += Messages_MeasureItem;
            _messages.DrawItem += Messages_DrawItem;
            _messages.DoubleClick += Messages_DoubleClick;

            rightPanel.Controls.Add(_messages);
            rightPanel.Controls.Add(bottomPanel);
            rightPanel.Controls.Add(_lblPeer);
            split.Panel2.Controls.Add(rightPanel);

            Controls.Add(split);
            Controls.Add(topBar);

            // ---------- 系统托盘 ----------
            _tray = new NotifyIcon
            {
                Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath),
                Text = "LS Chat",
                Visible = true
            };
            _tray.DoubleClick += (s, e) => ShowFromTray();
            var trayMenu = new ContextMenuStrip();
            trayMenu.Items.Add("打开", null, (s, e) => ShowFromTray());
            trayMenu.Items.Add("退出", null, (s, e) =>
            {
                _reallyExit = true;
                Close();
            });
            _tray.ContextMenuStrip = trayMenu;

            // ---------- 后台消息接收 ----------
            _pollTimer = new Timer { Interval = _api.UseWebSocket ? BackupPollIntervalMs : PollIntervalMs };
            _pollTimer.Tick += PollTimer_Tick;
            _pollTimer.Start();
            if (_api.UseWebSocket)
            {
                // WebSocket 模式：订阅实时推送并维持长连接（断线后自动重连）
                _api.MessageReceived += OnWsMessage;
                System.Threading.Tasks.Task.Run((Func<System.Threading.Tasks.Task>)WebSocketLoop);
            }

            Shown += async (s, e) =>
            {
                await RefreshUsers();
                await RefreshGroups();
                await LoadMyAvatar();
            };
        }

        // ================= 界面逻辑 =================

        private void ShowFromTray()
        {
            Show();
            WindowState = FormWindowState.Normal;
            Activate();
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!_reallyExit && e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
                _tray.ShowBalloonTip(2000, "LS Chat", "已最小化到系统托盘，仍在后台接收消息。", ToolTipIcon.Info);
                return;
            }
            _tray.Visible = false;
            base.OnFormClosing(e);
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                _cts.Cancel();
                _api.CloseWebSocket();
                _pollTimer.Stop();
                _pollTimer.Dispose();
                _tray.Dispose();
            }
            base.Dispose(disposing);
        }

        private async System.Threading.Tasks.Task RefreshUsers()
        {
            try
            {
                List<User> users = await _api.Users();
                _userNames.Clear();
                _userList.BeginUpdate();
                _userList.Items.Clear();
                foreach (User user in users)
                {
                    _userNames[user.id] = user.username;
                    if (user.id != _config.UserId)
                    {
                        _userList.Items.Add(user);
                    }
                }
                _userList.EndUpdate();
                await LoadAvatars(users);
            }
            catch
            {
                // 网络异常时保留现有列表
            }
        }

        private async System.Threading.Tasks.Task RefreshGroups()
        {
            try
            {
                List<GroupInfo> groups = await _api.MyGroups();
                _groupNames.Clear();
                _groupRoles.Clear();
                _groupList.BeginUpdate();
                _groupList.Items.Clear();
                foreach (GroupInfo group in groups)
                {
                    _groupNames[group.id] = group.name;
                    _groupRoles[group.id] = group.role;
                    _groupList.Items.Add(group);
                }
                _groupList.EndUpdate();
            }
            catch
            {
                // 网络异常时保留现有列表
            }
        }

        // ================= 头像 =================

        /// <summary>批量加载用户头像（带内存缓存，避免重复请求）。</summary>
        private async System.Threading.Tasks.Task LoadAvatars(IEnumerable<User> users)
        {
            bool changed = false;
            foreach (User user in users)
            {
                if (_avatars.ContainsKey(user.id)) continue;
                _avatars[user.id] = null; // 占位，避免轮询期间重复请求
                try
                {
                    string base64 = await _api.FetchAvatar(user.id);
                    if (base64.Length > 0)
                    {
                        byte[] bytes = Convert.FromBase64String(base64);
                        using (var stream = new MemoryStream(bytes))
                        using (Image tmp = Image.FromStream(stream))
                        {
                            _avatars[user.id] = new Bitmap(tmp);
                        }
                    }
                }
                catch
                {
                    // 加载失败保留占位（默认圆形字母）
                }
                changed = true;
            }
            if (changed) _userList.Invalidate();
        }

        /// <summary>按需加载某个用户的头像（用于消息气泡旁显示，带缓存）。</summary>
        private async void EnsureAvatar(long userId)
        {
            if (_avatars.ContainsKey(userId)) return;
            _avatars[userId] = null; // 占位，避免轮询期间重复请求
            try
            {
                string base64 = await _api.FetchAvatar(userId);
                if (base64.Length > 0)
                {
                    byte[] bytes = Convert.FromBase64String(base64);
                    using (var stream = new MemoryStream(bytes))
                    using (Image tmp = Image.FromStream(stream))
                    {
                        _avatars[userId] = new Bitmap(tmp);
                    }
                }
            }
            catch
            {
                // 加载失败保留占位（默认圆形字母）
            }
            _userList.Invalidate();
            _messages.Invalidate();
        }

        private async System.Threading.Tasks.Task LoadMyAvatar()
        {
            try
            {
                string base64 = await _api.FetchAvatar(_config.UserId);
                Image image = null;
                if (base64.Length > 0)
                {
                    byte[] bytes = Convert.FromBase64String(base64);
                    using (var stream = new MemoryStream(bytes))
                    using (Image tmp = Image.FromStream(stream))
                    {
                        image = new Bitmap(tmp);
                    }
                }
                _avatars[_config.UserId] = image;
                _selfAvatar.Image = image;
            }
            catch
            {
                // 无头像或网络异常时忽略
            }
        }

        /// <summary>点击自己的头像：选择图片并上传。</summary>
        private async System.Threading.Tasks.Task ChangeAvatar()
        {
            using (var dialog = new OpenFileDialog
            {
                Filter = "图片文件|*.jpg;*.jpeg;*.png;*.bmp;*.gif",
                Title = "选择头像图片"
            })
            {
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                try
                {
                    string base64 = CompressAvatar(dialog.FileName);
                    await _api.SetAvatar(base64);
                    await LoadMyAvatar();
                    _userList.Invalidate();
                }
                catch (Exception ex)
                {
                    MessageBox.Show(this, ex.Message, "设置头像失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                }
            }
        }

        private void UserList_MeasureItem(object sender, MeasureItemEventArgs e)
        {
            e.ItemHeight = 48;
        }

        private void UserList_DrawItem(object sender, DrawItemEventArgs e)
        {
            if (e.Index < 0 || e.Index >= _userList.Items.Count) return;
            var user = (User)_userList.Items[e.Index];
            e.DrawBackground();
            Graphics g = e.Graphics;
            var avatarBounds = new Rectangle(e.Bounds.X + 4, e.Bounds.Y + 10, 28, 28);
            Image avatar;
            if (_avatars.TryGetValue(user.id, out avatar) && avatar != null)
            {
                g.DrawImage(avatar, avatarBounds);
            }
            else
            {
                using (var brush = new SolidBrush(Color.FromArgb(150, 150, 150)))
                {
                    g.FillEllipse(brush, avatarBounds);
                }
                string initial = user.username.Length > 0 ? user.username.Substring(0, 1) : "?";
                TextRenderer.DrawText(g, initial, _userList.Font, avatarBounds, Color.White,
                    TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
            }
            TextRenderer.DrawText(g, user.username, _userList.Font,
                new Rectangle(e.Bounds.X + 40, e.Bounds.Y + 5, e.Bounds.Width - 44, 20),
                e.ForeColor, TextFormatFlags.Left | TextFormatFlags.VerticalCenter);
            string subtitle = user.Subtitle;
            if (subtitle.Length > 0)
            {
                using (var smallFont = new Font(_userList.Font.FontFamily, Math.Max(7f, _userList.Font.Size - 2f)))
                {
                    TextRenderer.DrawText(g, subtitle, smallFont,
                        new Rectangle(e.Bounds.X + 40, e.Bounds.Y + 26, e.Bounds.Width - 44, 16),
                        Color.Gray, TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
                }
            }
            e.DrawFocusRectangle();
        }

        private async void UserList_SelectedIndexChanged(object sender, EventArgs e)
        {
            User user = _userList.SelectedItem as User;
            if (user == null || user.id == _currentPeerId) return;
            _currentPeerId = user.id;
            _currentGroupId = -1;
            _lblPeer.Text = "与 " + user.username + " 的对话";
            _messages.Items.Clear();
            try
            {
                List<ChatMessage> history = await _api.FetchMessages(user.id, 0);
                foreach (ChatMessage message in history)
                {
                    AddMessageItem(message);
                    if (message.from == user.id && message.id > _config.LastSeenMessageId)
                    {
                        _config.LastSeenMessageId = message.id;
                    }
                }
                _config.Save();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "加载消息失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private async void GroupList_SelectedIndexChanged(object sender, EventArgs e)
        {
            GroupInfo group = _groupList.SelectedItem as GroupInfo;
            if (group == null || group.id == _currentGroupId) return;
            _currentGroupId = group.id;
            _currentPeerId = -1;
            _lblPeer.Text = "群聊：" + group.name + (group.mutedAll ? "（全员禁言中）" : "");
            _messages.Items.Clear();
            try
            {
                List<ChatMessage> history = await _api.FetchGroupMessages(group.id, 0);
                foreach (ChatMessage message in history)
                {
                    AddMessageItem(message);
                    if (message.id > _config.LastSeenMessageId)
                    {
                        _config.LastSeenMessageId = message.id;
                    }
                }
                _config.Save();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "加载消息失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private async void BtnVideo_Click(object sender, EventArgs e)
        {
            if (_currentGroupId <= 0 && _currentPeerId <= 0) return;
            using (var dialog = new OpenFileDialog
            {
                Filter = "视频文件|*.mp4;*.mkv;*.avi;*.mov;*.3gp;*.wmv",
                Title = "选择视频（最大 40MB）"
            })
            {
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                try
                {
                    var info = new FileInfo(dialog.FileName);
                    if (info.Length > 40L * 1024 * 1024)
                    {
                        MessageBox.Show(this, "视频过大，最大支持 40MB", "无法发送",
                            MessageBoxButtons.OK, MessageBoxIcon.Warning);
                        return;
                    }
                    string base64 = Convert.ToBase64String(File.ReadAllBytes(dialog.FileName));
                    ChatMessage message;
                    if (_currentGroupId > 0)
                    {
                        message = await _api.SendGroupMessage(_currentGroupId, ChatMessage.TypeVideo, base64);
                    }
                    else
                    {
                        message = await _api.Send(_currentPeerId, ChatMessage.TypeVideo, base64);
                    }
                    AddMessageItem(message);
                }
                catch (Exception ex)
                {
                    MessageBox.Show(this, ex.Message, "发送视频失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                }
            }
        }

        private async void BtnSend_Click(object sender, EventArgs e)
        {
            string text = _input.Text.Trim();
            if (text.Length == 0) return;
            try
            {
                ChatMessage message;
                if (_currentGroupId > 0)
                {
                    message = await _api.SendGroupMessage(_currentGroupId, ChatMessage.TypeText, text);
                }
                else if (_currentPeerId > 0)
                {
                    message = await _api.Send(_currentPeerId, ChatMessage.TypeText, text);
                }
                else
                {
                    return;
                }
                _input.Clear();
                AddMessageItem(message);
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "发送失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private async void BtnImage_Click(object sender, EventArgs e)
        {
            if (_currentGroupId < 0 && _currentPeerId < 0) return;
            using (var dialog = new OpenFileDialog
            {
                Filter = "图片文件|*.jpg;*.jpeg;*.png;*.bmp;*.gif",
                Title = "选择要发送的图片"
            })
            {
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                try
                {
                    string base64 = CompressImage(dialog.FileName);
                    ChatMessage message = _currentGroupId > 0
                        ? await _api.SendGroupMessage(_currentGroupId, ChatMessage.TypeImage, base64)
                        : await _api.Send(_currentPeerId, ChatMessage.TypeImage, base64);
                    AddMessageItem(message);
                }
                catch (Exception ex)
                {
                    MessageBox.Show(this, ex.Message, "图片发送失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                }
            }
        }

        // ================= 好友操作 =================

        private async void BtnAddFriend_Click(object sender, EventArgs e)
        {
            string text = Microsoft.VisualBasic.Interaction.InputBox(
                "输入要添加的用户 ID（可向对方询问其 ID）：", "添加好友", "", -1, -1);
            long userId;
            if (string.IsNullOrWhiteSpace(text) || !long.TryParse(text.Trim(), out userId)) return;
            try
            {
                User user = await _api.LookupUser(userId);
                if (MessageBox.Show(this, "用户名：" + user.username + "\nID：" + user.id +
                        "\n\n是否发送好友申请？", "确认添加",
                        MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
                string result = await _api.SendFriendRequest(user.id);
                MessageBox.Show(this, result, "添加好友", MessageBoxButtons.OK, MessageBoxIcon.Information);
                await RefreshUsers();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "添加失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private async void FriendRequests_Click(object sender, EventArgs e)
        {
            try
            {
                List<FriendRequest> requests = await _api.FriendRequests();
                using (var dialog = new FriendRequestsForm(_api, requests))
                {
                    dialog.ShowDialog(this);
                }
                await RefreshUsers();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "错误", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        // ================= 群组操作 =================

        private async void BtnCreateGroup_Click(object sender, EventArgs e)
        {
            using (var dialog = new CreateGroupForm())
            {
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                try
                {
                    GroupInfo group = await _api.CreateGroup(dialog.GroupName, dialog.JoinPolicy);
                    await RefreshGroups();
                    MessageBox.Show(this, "群已创建，群 ID：" + group.id + "（可把 ID 分享给他人搜索加入）",
                        "创建成功", MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
                catch (Exception ex)
                {
                    MessageBox.Show(this, ex.Message, "创建失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                }
            }
        }

        private async void BtnSearchGroup_Click(object sender, EventArgs e)
        {
            string text = Microsoft.VisualBasic.Interaction.InputBox("输入要搜索的群 ID：", "搜索群组", "", -1, -1);
            long groupId;
            if (string.IsNullOrWhiteSpace(text) || !long.TryParse(text.Trim(), out groupId)) return;
            try
            {
                GroupInfo group = await _api.SearchGroup(groupId);
                string policy = group.joinPolicy == "open" ? "允许直接加入" : "需要管理员审核";
                string message = "群名称：" + group.name + "\n群 ID：" + group.id +
                                 "\n成员数：" + group.memberCount + "\n入群方式：" + policy +
                                 (group.role != null ? "\n你已是该群成员" : "");
                if (group.role == null &&
                    MessageBox.Show(this, message + "\n\n是否加入？", "搜索到群组",
                        MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes)
                {
                    string result = await _api.JoinGroup(group.id);
                    MessageBox.Show(this, result, "加入群组", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    await RefreshGroups();
                }
                else if (group.role != null)
                {
                    MessageBox.Show(this, message, "搜索到群组", MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "搜索失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private async void GroupManage_Click(object sender, EventArgs e)
        {
            GroupInfo group = _groupList.SelectedItem as GroupInfo;
            if (group == null)
            {
                MessageBox.Show(this, "请先在左侧选择一个群组", "提示", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            if (!group.CanManage)
            {
                MessageBox.Show(this, "只有群主或管理员可以管理群组", "提示", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            try
            {
                // 拉取最新信息（含角色）再打开管理窗口
                List<GroupInfo> groups = await _api.MyGroups();
                GroupInfo latest = null;
                foreach (GroupInfo g in groups)
                {
                    if (g.id == group.id) { latest = g; break; }
                }
                if (latest == null || !latest.CanManage)
                {
                    MessageBox.Show(this, "无法获取群组信息", "错误", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }
                using (var manageForm = new GroupManageForm(_api, latest))
                {
                    manageForm.ShowDialog(this);
                }
                await RefreshGroups();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "错误", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private async void GroupRequests_Click(object sender, EventArgs e)
        {
            try
            {
                List<JoinRequest> requests = await _api.GroupRequests();
                using (var dialog = new JoinRequestsForm(_api, requests))
                {
                    dialog.ShowDialog(this);
                }
                await RefreshGroups();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "错误", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        // ================= 后台消息接收 =================

        /// <summary>WebSocket 模式：维持长连接，断开后 3 秒重连。</summary>
        private async System.Threading.Tasks.Task WebSocketLoop()
        {
            while (!_cts.IsCancellationRequested)
            {
                try
                {
                    await _api.EnsureWebSocketAsync();
                    await _api.WaitForWebSocketDisconnectAsync();
                }
                catch
                {
                    // 连接失败，稍后重试
                }
                try
                {
                    await System.Threading.Tasks.Task.Delay(3000, _cts.Token);
                }
                catch
                {
                    break;
                }
            }
        }

        /// <summary>WebSocket 推送到达：切换到 UI 线程处理。</summary>
        private void OnWsMessage(ChatMessage message)
        {
            if (IsDisposed) return;
            try
            {
                BeginInvoke(new Action(() => ProcessIncoming(new List<ChatMessage> { message })));
            }
            catch
            {
                // 窗体已销毁时忽略
            }
        }

        private async void PollTimer_Tick(object sender, EventArgs e)
        {
            if (_polling) return;
            _polling = true;
            try
            {
                List<ChatMessage> incoming = await _api.FetchIncoming(_config.LastSeenMessageId);
                ProcessIncoming(incoming);

                if (++_refreshCounter >= 5)
                {
                    _refreshCounter = 0;
                    await RefreshUsers();
                    await RefreshGroups();
                }
            }
            catch
            {
                // 网络异常时静默跳过本轮
            }
            finally
            {
                _polling = false;
            }
        }

        /// <summary>处理一批新消息：更新已读位置、当前会话上屏、其余弹托盘通知。</summary>
        private void ProcessIncoming(List<ChatMessage> incoming)
        {
            bool changed = false;
            foreach (ChatMessage message in incoming)
            {
                // 按消息 id 去重（实时推送与兜底轮询可能重复投递）
                if (message.id <= _config.LastSeenMessageId) continue;
                _config.LastSeenMessageId = message.id;
                changed = true;
                bool isCurrent = message.group > 0
                    ? message.group == _currentGroupId
                    : message.from == _currentPeerId;
                if (isCurrent)
                {
                    AddMessageItem(message);
                }
                else
                {
                    NotifyMessage(message);
                }
            }
            if (changed) _config.Save();
        }

        private void NotifyMessage(ChatMessage message)
        {
            string senderName;
            if (!_userNames.TryGetValue(message.from, out senderName))
            {
                senderName = "用户 #" + message.from;
            }
            string text = message.type == ChatMessage.TypeImage ? "[图片]" : message.content;
            if (message.group > 0)
            {
                string groupName;
                if (!_groupNames.TryGetValue(message.group, out groupName))
                {
                    groupName = "群 #" + message.group;
                }
                _tray.ShowBalloonTip(3000, "[群] " + groupName, senderName + "：" + text, ToolTipIcon.Info);
            }
            else
            {
                _tray.ShowBalloonTip(3000, senderName, text, ToolTipIcon.Info);
            }
        }

        // ================= 消息列表（自绘气泡） =================

        private class MessageItem
        {
            public ChatMessage Message;
            public Bitmap Image;
            public bool IsSelf;
            public string SenderName;

            public bool ShowSender
            {
                get { return SenderName != null; }
            }

            /// <summary>列表中显示的文本（视频消息显示占位文字，不显示 Base64）。</summary>
            public string DisplayText
            {
                get
                {
                    if (Message.type == ChatMessage.TypeVideo) return "[视频] 双击查看或保存";
                    return Message.content;
                }
            }

            public override string ToString()
            {
                return DisplayText;
            }
        }

        private void AddMessageItem(ChatMessage message)
        {
            EnsureAvatar(message.from);
            var item = new MessageItem
            {
                Message = message,
                IsSelf = message.from == _config.UserId
            };
            // 群聊中显示他人消息的发送者
            if (message.group > 0 && !item.IsSelf)
            {
                string senderName;
                item.SenderName = _userNames.TryGetValue(message.from, out senderName)
                    ? senderName
                    : "用户 #" + message.from;
            }
            if (message.type == ChatMessage.TypeImage)
            {
                try
                {
                    byte[] bytes = Convert.FromBase64String(message.content);
                    using (var stream = new MemoryStream(bytes))
                    using (Image original = Image.FromStream(stream))
                    {
                        double scale = Math.Min(1.0,
                            Math.Min(MaxImageWidth / (double)original.Width, MaxImageHeight / (double)original.Height));
                        int w = Math.Max(1, (int)(original.Width * scale));
                        int h = Math.Max(1, (int)(original.Height * scale));
                        var thumb = new Bitmap(w, h);
                        using (Graphics g = Graphics.FromImage(thumb))
                        {
                            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                            g.DrawImage(original, 0, 0, w, h);
                        }
                        item.Image = thumb;
                    }
                }
                catch
                {
                    item.Image = null;
                }
            }
            _messages.Items.Add(item);
            _messages.TopIndex = _messages.Items.Count - 1;
        }

        private void Messages_MeasureItem(object sender, MeasureItemEventArgs e)
        {
            var item = (MessageItem)_messages.Items[e.Index];
            int maxWidth = _messages.ClientSize.Width - 140;
            int height = item.ShowSender ? 18 : 0;
            if (item.Image != null)
            {
                height += ScaledImageHeight(item.Image, maxWidth) + 16;
            }
            else
            {
                Size size = TextRenderer.MeasureText(item.DisplayText, _messages.Font,
                    new Size(maxWidth, int.MaxValue),
                    TextFormatFlags.WordBreak | TextFormatFlags.TextBoxControl);
                height += size.Height + 16;
            }
            e.ItemHeight = Math.Max(height, 36);
        }

        /// <summary>在消息项旁绘制 28x28 圆形头像（无头像时灰色圆形 + 首字母）。</summary>
        private void DrawMessageAvatar(Graphics g, Rectangle bounds, long userId, string nameHint)
        {
            Image avatar;
            if (_avatars.TryGetValue(userId, out avatar) && avatar != null)
            {
                using (var path = new GraphicsPath())
                {
                    path.AddEllipse(bounds);
                    g.SetClip(path);
                    g.DrawImage(avatar, bounds);
                    g.ResetClip();
                }
            }
            else
            {
                using (var brush = new SolidBrush(Color.FromArgb(150, 150, 150)))
                {
                    g.FillEllipse(brush, bounds);
                }
                string initial = nameHint != null && nameHint.Length > 0 ? nameHint.Substring(0, 1) : "?";
                TextRenderer.DrawText(g, initial, _messages.Font, bounds, Color.White,
                    TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
            }
        }

        private void Messages_DrawItem(object sender, DrawItemEventArgs e)
        {
            if (e.Index < 0) return;
            var item = (MessageItem)_messages.Items[e.Index];
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.FillRectangle(SystemBrushes.Window, e.Bounds);

            // 头像固定在气泡一侧：他人消息在左，自己消息在右
            var avatarBounds = new Rectangle(
                item.IsSelf ? e.Bounds.Right - 36 : e.Bounds.X + 8,
                e.Bounds.Y + 4, 28, 28);
            string avatarName = item.ShowSender ? item.SenderName
                : (item.IsSelf ? _config.UserName
                : (_userNames.ContainsKey(item.Message.from) ? _userNames[item.Message.from] : null));
            DrawMessageAvatar(g, avatarBounds, item.Message.from, avatarName);

            int top = e.Bounds.Y + 4;
            if (item.ShowSender)
            {
                TextRenderer.DrawText(g, item.SenderName, _messages.Font,
                    new Rectangle(44, top, 200, 16),
                    Color.Gray, TextFormatFlags.Left);
                top += 16;
            }

            int maxWidth = _messages.ClientSize.Width - 140;
            if (item.Image != null)
            {
                int w = Math.Min(item.Image.Width, maxWidth);
                int h = ScaledImageHeight(item.Image, maxWidth);
                Rectangle bubble = new Rectangle(
                    item.IsSelf ? avatarBounds.X - 8 - w - 16 : 44,
                    top, w + 16, h + 12);
                DrawBubble(g, bubble, item.IsSelf);
                g.DrawImage(item.Image, bubble.X + 8, bubble.Y + 6, w, h);
            }
            else
            {
                Size size = TextRenderer.MeasureText(item.DisplayText, _messages.Font,
                    new Size(maxWidth - 24, int.MaxValue),
                    TextFormatFlags.WordBreak | TextFormatFlags.TextBoxControl);
                int w = size.Width + 24;
                Rectangle bubble = new Rectangle(
                    item.IsSelf ? avatarBounds.X - 8 - w : 44,
                    top, w, size.Height + 12);
                DrawBubble(g, bubble, item.IsSelf);
                TextRenderer.DrawText(g, item.DisplayText, _messages.Font,
                    new Rectangle(bubble.X + 12, bubble.Y + 6, size.Width, size.Height),
                    Color.Black, TextFormatFlags.WordBreak | TextFormatFlags.TextBoxControl);
            }
        }

        private static int ScaledImageHeight(Image image, int maxWidth)
        {
            int w = Math.Min(image.Width, maxWidth);
            return (int)((long)image.Height * w / image.Width);
        }

        private static void DrawBubble(Graphics g, Rectangle bounds, bool isSelf)
        {
            Color color = isSelf ? Color.FromArgb(204, 231, 255) : Color.FromArgb(240, 240, 240);
            using (var brush = new SolidBrush(color))
            using (var path = RoundedRect(bounds, 12))
            {
                g.FillPath(brush, path);
            }
        }

        private static GraphicsPath RoundedRect(Rectangle bounds, int radius)
        {
            int d = radius * 2;
            var path = new GraphicsPath();
            path.AddArc(bounds.X, bounds.Y, d, d, 180, 90);
            path.AddArc(bounds.Right - d, bounds.Y, d, d, 270, 90);
            path.AddArc(bounds.Right - d, bounds.Bottom - d, d, d, 0, 90);
            path.AddArc(bounds.X, bounds.Bottom - d, d, d, 90, 90);
            path.CloseFigure();
            return path;
        }

        /// <summary>双击图片/视频消息：打开查看窗口（可播放/保存）。</summary>
        private void Messages_DoubleClick(object sender, EventArgs e)
        {
            var item = _messages.SelectedItem as MessageItem;
            if (item == null) return;
            if (item.Message.type != ChatMessage.TypeImage && item.Message.type != ChatMessage.TypeVideo) return;
            using (var viewer = new MediaViewerForm(item.Message))
            {
                viewer.ShowDialog(this);
            }
        }

        // ================= 图片压缩 =================

        /// <summary>缩放并压缩为 JPEG Base64（最长边 1280，质量 80）。</summary>
        private static string CompressImage(string path)
        {
            return CompressToJpegBase64(path, 1280, 80L);
        }

        /// <summary>缩放并压缩头像为 JPEG Base64（最长边 256，质量 85）。</summary>
        private static string CompressAvatar(string path)
        {
            return CompressToJpegBase64(path, 256, 85L);
        }

        private static string CompressToJpegBase64(string path, int maxSide, long quality)
        {
            using (Image original = Image.FromFile(path))
            {
                double scale = Math.Min(1.0,
                    Math.Min(maxSide / (double)original.Width, maxSide / (double)original.Height));
                int w = Math.Max(1, (int)(original.Width * scale));
                int h = Math.Max(1, (int)(original.Height * scale));
                using (var scaled = new Bitmap(w, h))
                {
                    using (Graphics g = Graphics.FromImage(scaled))
                    {
                        g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                        g.DrawImage(original, 0, 0, w, h);
                    }
                    ImageCodecInfo codec = GetJpegCodec();
                    using (var parameters = new EncoderParameters(1))
                    {
                        parameters.Param[0] = new EncoderParameter(Encoder.Quality, quality);
                        using (var stream = new MemoryStream())
                        {
                            scaled.Save(stream, codec, parameters);
                            return Convert.ToBase64String(stream.ToArray());
                        }
                    }
                }
            }
        }

        private static ImageCodecInfo GetJpegCodec()
        {
            foreach (ImageCodecInfo codec in ImageCodecInfo.GetImageEncoders())
            {
                if (codec.MimeType == "image/jpeg") return codec;
            }
            throw new InvalidOperationException("找不到 JPEG 编码器");
        }
    }
}
