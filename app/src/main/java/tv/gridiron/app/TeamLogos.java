package tv.gridiron.app;

/** Bundled NFL artwork; no network, worker threads, or separate bitmap cache. */
final class TeamLogos {
    static int resource(String abbreviation){
        if(abbreviation==null)return 0;
        switch(abbreviation.trim().toUpperCase(java.util.Locale.US)){
            case "ARI": return R.drawable.nfl_ari;
            case "ATL": return R.drawable.nfl_atl;
            case "BAL": return R.drawable.nfl_bal;
            case "BUF": return R.drawable.nfl_buf;
            case "CAR": return R.drawable.nfl_car;
            case "CHI": return R.drawable.nfl_chi;
            case "CIN": return R.drawable.nfl_cin;
            case "CLE": return R.drawable.nfl_cle;
            case "DAL": return R.drawable.nfl_dal;
            case "DEN": return R.drawable.nfl_den;
            case "DET": return R.drawable.nfl_det;
            case "GB": return R.drawable.nfl_gb;
            case "HOU": return R.drawable.nfl_hou;
            case "IND": return R.drawable.nfl_ind;
            case "JAX": case "JAC": return R.drawable.nfl_jax;
            case "KC": return R.drawable.nfl_kc;
            case "LV": return R.drawable.nfl_lv;
            case "LAC": return R.drawable.nfl_lac;
            case "LAR": case "LA": return R.drawable.nfl_lar;
            case "MIA": return R.drawable.nfl_mia;
            case "MIN": return R.drawable.nfl_min;
            case "NE": return R.drawable.nfl_ne;
            case "NO": return R.drawable.nfl_no;
            case "NYG": return R.drawable.nfl_nyg;
            case "NYJ": return R.drawable.nfl_nyj;
            case "PHI": return R.drawable.nfl_phi;
            case "PIT": return R.drawable.nfl_pit;
            case "SF": return R.drawable.nfl_sf;
            case "SEA": return R.drawable.nfl_sea;
            case "TB": return R.drawable.nfl_tb;
            case "TEN": return R.drawable.nfl_ten;
            case "WSH": case "WAS": return R.drawable.nfl_wsh;
            default:return 0; // The scoreboard's team text remains visible for unknown teams.
        }
    }
}
