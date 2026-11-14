import 'package:flutter/material.dart';

import 'platform.dart';

const _months = [
  'Oca', 'Şub', 'Mar', 'Nis', 'May', 'Haz', 'Tem', 'Ağu', 'Eyl', 'Eki', 'Kas', 'Ara', //
];

String _two(int n) => n.toString().padLeft(2, '0');

String formatClock(DateTime d) => '${_two(d.hour)}:${_two(d.minute)}';

/// Bugün: saat; bu yıl: "5 Kas"; daha eski: "05.11.2024".
String formatListTime(DateTime d) {
  final now = DateTime.now();
  if (d.year == now.year && d.month == now.month && d.day == now.day) {
    return formatClock(d);
  }
  if (d.year == now.year) return '${d.day} ${_months[d.month - 1]}';
  return '${_two(d.day)}.${_two(d.month)}.${d.year}';
}

String formatDay(DateTime d) {
  final now = DateTime.now();
  final today = DateTime(now.year, now.month, now.day);
  final diff = today.difference(DateTime(d.year, d.month, d.day)).inDays;
  if (diff == 0) return 'Bugün';
  if (diff == 1) return 'Dün';
  final base = '${d.day} ${_months[d.month - 1]}';
  return d.year == now.year ? base : '$base ${d.year}';
}

/// SIM'in sabit renginde küçük rozet ("SIM 1").
class SimBadge extends StatelessWidget {
  const SimBadge(this.sim, {super.key});

  final SimInfo sim;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: sim.color,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        sim.label,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 10,
          height: 1.2,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

/// Gönderilecek SIM'i seçmek için chip'ler.
class SimSelector extends StatelessWidget {
  const SimSelector({
    super.key,
    required this.sims,
    required this.selected,
    required this.onSelected,
  });

  final List<SimInfo> sims;
  final int? selected;
  final ValueChanged<int> onSelected;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          for (final sim in sims)
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: ChoiceChip(
                avatar: CircleAvatar(backgroundColor: sim.color, radius: 6),
                showCheckmark: false,
                label: Text(sim.fullLabel),
                selected: sim.subId == selected,
                selectedColor: sim.color.withValues(alpha: 0.25),
                side: sim.subId == selected ? BorderSide(color: sim.color) : null,
                visualDensity: VisualDensity.compact,
                onSelected: (_) => onSelected(sim.subId),
              ),
            ),
        ],
      ),
    );
  }
}
