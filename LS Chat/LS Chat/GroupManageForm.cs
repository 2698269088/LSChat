using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;

namespace LS_Chat
{
    /// <summary>
    /// 群管理窗口（群主/管理员）：修改群名称、入群方式、全员禁言、
    /// 设置/取消管理员（仅群主）、禁言/解除禁言指定成员。
    /// </summary>
    public class GroupManageForm : Form
    {
        private readonly ApiClient _api;
        private readonly GroupInfo _group;
        private readonly Label _lblInfo;
        private readonly ListBox _members;
        private readonly ComboBox _cmbPolicy;
        private readonly CheckBox _chkMutedAll;
        private readonly Button _btnRole;
        private readonly Button _btnMute;
        private List<GroupMember> _memberList = new List<GroupMember>();

        private bool IsOwner
        {
            get { return _group.role == "owner"; }
        }

        public GroupManageForm(ApiClient api, GroupInfo group)
        {
            _api = api;
            _group = group;

            Text = "群管理 - " + group.name;
            StartPosition = FormStartPosition.CenterParent;
            ClientSize = new Size(470, 470);
            MinimumSize = new Size(440, 430);

            // ---------- 顶部：群信息与改名 ----------
            var topPanel = new Panel { Dock = DockStyle.Top, Height = 96 };
            string myRole = IsOwner ? "群主" : "管理员";
            _lblInfo = new Label
            {
                Text = "群名称：" + group.name + "\n群 ID：" + group.id + "　我的角色：" + myRole,
                Left = 12,
                Top = 10,
                Width = 300,
                Height = 46
            };
            var btnRename = new Button { Text = "修改群名", Left = 330, Top = 10, Width = 120 };
            btnRename.Click += BtnRename_Click;
            _cmbPolicy = new ComboBox
            {
                Left = 12,
                Top = 58,
                Width = 220,
                DropDownStyle = ComboBoxStyle.DropDownList
            };
            _cmbPolicy.Items.Add("入群方式：需要管理员审核");
            _cmbPolicy.Items.Add("入群方式：允许直接加入");
            _cmbPolicy.SelectedIndex = group.joinPolicy == "open" ? 1 : 0;
            _chkMutedAll = new CheckBox
            {
                Text = "全员禁言（仅群主/管理员可发言）",
                Left = 248,
                Top = 60,
                Width = 210,
                Checked = group.mutedAll
            };
            var btnSave = new Button { Text = "保存设置", Left = 330, Top = 58, Width = 120 };
            btnSave.Click += BtnSaveSettings_Click;
            topPanel.Controls.Add(_lblInfo);
            topPanel.Controls.Add(btnRename);
            topPanel.Controls.Add(_cmbPolicy);
            topPanel.Controls.Add(_chkMutedAll);
            topPanel.Controls.Add(btnSave);

            // ---------- 中部：成员列表 ----------
            var memberPanel = new Panel { Dock = DockStyle.Fill, Padding = new Padding(12, 100, 12, 60) };
            _members = new ListBox
            {
                Dock = DockStyle.Fill,
                IntegralHeight = false,
                HorizontalScrollbar = true
            };
            _members.SelectedIndexChanged += (s, e) => UpdateButtons();
            memberPanel.Controls.Add(_members);

            // ---------- 底部：成员操作 ----------
            var bottomPanel = new Panel { Dock = DockStyle.Bottom, Height = 54 };
            _btnRole = new Button { Text = "设为管理员", Left = 12, Top = 10, Width = 110 };
            _btnRole.Click += BtnRole_Click;
            _btnMute = new Button { Text = "禁言", Left = 132, Top = 10, Width = 110 };
            _btnMute.Click += BtnMute_Click;
            var btnRefresh = new Button { Text = "刷新成员", Left = 252, Top = 10, Width = 100 };
            btnRefresh.Click += async (s, e) => await LoadMembers();
            var btnClose = new Button
            {
                Text = "关闭",
                Left = 375,
                Top = 10,
                Width = 80,
                Anchor = AnchorStyles.Top | AnchorStyles.Right,
                DialogResult = DialogResult.Cancel
            };
            bottomPanel.Controls.Add(_btnRole);
            bottomPanel.Controls.Add(_btnMute);
            bottomPanel.Controls.Add(btnRefresh);
            bottomPanel.Controls.Add(btnClose);

            Controls.Add(memberPanel);
            Controls.Add(bottomPanel);
            Controls.Add(topPanel);
            CancelButton = btnClose;

            Shown += async (s, e) => await LoadMembers();
        }

        private GroupMember SelectedMember
        {
            get { return _members.SelectedItem as GroupMember; }
        }

        private void UpdateButtons()
        {
            GroupMember member = SelectedMember;
            if (member == null)
            {
                _btnRole.Enabled = false;
                _btnMute.Enabled = false;
                return;
            }
            bool isOwnerTarget = member.role == "owner";
            // 只有群主可以设置/取消管理员，且不能操作群主
            _btnRole.Enabled = IsOwner && !isOwnerTarget;
            _btnRole.Text = member.role == "admin" ? "取消管理员" : "设为管理员";
            // 不能禁言群主；只有群主可以禁言管理员
            bool canMute = !isOwnerTarget && (member.role != "admin" || IsOwner);
            _btnMute.Enabled = canMute;
            _btnMute.Text = member.muted ? "解除禁言" : "禁言";
        }

        private async System.Threading.Tasks.Task LoadMembers()
        {
            try
            {
                _memberList = await _api.GroupMembers(_group.id);
                _members.BeginUpdate();
                _members.Items.Clear();
                foreach (GroupMember member in _memberList)
                {
                    _members.Items.Add(member);
                }
                _members.EndUpdate();
                UpdateButtons();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "加载成员失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private async void BtnRename_Click(object sender, EventArgs e)
        {
            string name = Microsoft.VisualBasic.Interaction.InputBox(
                "输入新的群名称：", "修改群名", _group.name, -1, -1);
            name = (name ?? "").Trim();
            if (name.Length == 0 || name == _group.name) return;
            try
            {
                await _api.RenameGroup(_group.id, name);
                _group.name = name;
                _lblInfo.Text = "群名称：" + _group.name + "\n群 ID：" + _group.id +
                                "　我的角色：" + (IsOwner ? "群主" : "管理员");
                Text = "群管理 - " + _group.name;
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "修改失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private async void BtnSaveSettings_Click(object sender, EventArgs e)
        {
            try
            {
                string policy = _cmbPolicy.SelectedIndex == 1 ? "open" : "approval";
                await _api.UpdateGroupSettings(_group.id, policy, _chkMutedAll.Checked);
                _group.joinPolicy = policy;
                _group.mutedAll = _chkMutedAll.Checked;
                MessageBox.Show(this, "设置已保存", "提示",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "保存失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private async void BtnRole_Click(object sender, EventArgs e)
        {
            GroupMember member = SelectedMember;
            if (member == null) return;
            string newRole = member.role == "admin" ? "member" : "admin";
            string action = newRole == "admin" ? "设为管理员" : "取消管理员";
            if (MessageBox.Show(this, "确定将 " + member.username + " " + action + "？",
                    "确认", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes)
            {
                return;
            }
            try
            {
                await _api.SetGroupRole(_group.id, member.id, newRole);
                await LoadMembers();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "操作失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private async void BtnMute_Click(object sender, EventArgs e)
        {
            GroupMember member = SelectedMember;
            if (member == null) return;
            bool muted = !member.muted;
            try
            {
                await _api.SetGroupMute(_group.id, member.id, muted);
                await LoadMembers();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "操作失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }
    }
}
